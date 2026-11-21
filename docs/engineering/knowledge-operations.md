# Local knowledge ingestion and retrieval operations

Follow [ADR 0004](../architecture/decisions/0004-knowledge-ingestion-and-retrieval.md). This is a local CPU foundation, not production deployment guidance. Upload success means durable PENDING work, not completed embedding. Never use development/production data as automated test fixtures.

## Prepare the local model explicitly

Prerequisites: Docker Engine with Compose v2.24.4+ (`!reset` support), JDK 21 for host Java tests, Python 3.12 and uv 0.13.9 for host Python commands. Network is needed for dependency installation and this explicit model setup only. The official model uses the MIT license; review [its model card](https://huggingface.co/intfloat/multilingual-e5-small).

From repository root:

```sh
./dev.sh init
cd services/agent-runtime
uv sync --locked
uv run --locked python -m agent_runtime.embedding.setup "$HOME/.cache/eaap/models/e5-small"
HF_HUB_OFFLINE=1 uv run --locked python -m agent_runtime.embedding.smoke "$HOME/.cache/eaap/models/e5-small"
cd ../..
# Public model artifacts only: the non-root container must be able to read them.
chmod -R a+rX "$HOME/.cache/eaap/models/e5-small"
```

The setup command downloads the fixed official ONNX/tokenizer files at revision `614241f622f53c4eeff9890bdc4f31cfecc418b3`, recording a local digest manifest. It neither selects arbitrary models nor accepts an arbitrary download URL. Runtime does not download anything; an absent/corrupt model produces 503 `embedding_unavailable` and ingestion FAILED/EMBEDDING_UNAVAILABLE. Fix the model and explicitly retry FAILED documents. Model initialization is lazy and serialized; `/health` does not assert model readiness.

Allow roughly 465 MiB for prepared model files plus Python dependencies/container layers and setup download overhead. Measured CPU adapter peak RSS was about 913 MiB; the knowledge overlay grants 1.5 GiB to Python. There is no GPU requirement. Keep model files outside the repository/build context; do not mount signing keys or private source directories here.

Generate the shared secret in ignored `.env`, preserving nonempty settings, without printing it:

```sh
python3 - <<'PY'
from pathlib import Path
import os, secrets, tempfile
path = Path('.env')
lines = path.read_text().splitlines()
values = dict(line.split('=', 1) for line in lines if '=' in line and not line.lstrip().startswith('#'))
updates = {
    'EMBEDDING_SECRET': values.get('EMBEDDING_SECRET') or secrets.token_urlsafe(48),
    'EMBEDDING_MODEL_DIR': values.get('EMBEDDING_MODEL_DIR') or str(Path.home() / '.cache/eaap/models/e5-small'),
}
lines = [line for line in lines if line.split('=', 1)[0] not in updates]
fd, name = tempfile.mkstemp(prefix='.env.tmp.', dir='.')
with os.fdopen(fd, 'w') as stream:
    stream.write('\n'.join(lines + [f'{k}={v}' for k, v in updates.items()]) + '\n')
os.replace(name, path)
print('Knowledge settings prepared; secret not displayed.')
PY
```

Use a random 32–128-character visible ASCII secret. Java/Python receive the same setting through the opt-in overlay; it is never a VITE setting, image argument or browser token. Configure your existing RSA keys, issuer, audience and key ID using [authentication operations](authentication-operations.md), then make those settings available to Compose through environment or ignored `.env`. No customer identity/bootstrap is created automatically by knowledge setup.

## Provision pgvector and apply V4

Back up/review an existing database first using [database operations](database-operations.md). Keep backup files protected outside the repository. These commands preserve the volume; never run `down --volumes` on your normal development stack to fix a migration.

```sh
docker compose config --quiet
docker compose up -d --wait postgres
docker compose build control-plane agent-runtime
# Dedicated role provisioning and extension creation are administrator-only operations.
docker compose exec -T postgres sh /docker-entrypoint-initdb.d/10-platform-roles.sh
docker compose exec -T postgres sh -c \
  'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /opt/eaap/knowledge-extension.sql'
# The migration overlay forwards only the migration credential to this temporary process.
docker compose -f compose.yaml -f compose.migration.yaml run --rm --no-deps control-plane
```

The operator owns `extensions.vector`; runtime and migration only receive schema USAGE. V4 refuses to run without the expected extension namespace. Repeated provisioning/migration is safe. An existing extension in a different schema stops setup for review; do not relocate/drop it automatically. Schema migrations never execute under HTTP credentials. `./dev.sh up` also runs the explicit administrator extension operation before its existing migration step; it starts the baseline stack with knowledge worker disabled. To enable knowledge and preserve signing configuration, use the overlays below.

```sh
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml config --quiet
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml up -d --build --wait
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml ps
```

Exactly four services remain. The overlay removes Python's host port, mounts model artifacts read-only, enables the single Java worker, and increases only Python's memory cap. PostgreSQL stays private. Browser/customer clients use `http://127.0.0.1:8080`; they must not call the embedding endpoint. The shared secret protects requests inside the trusted local network; this is not TLS or production service identity. Migration/operator credentials do not enter the ordinary Java service; Python receives no database/customer JWT credentials.

## Small sample document

Log in using the existing authentication API. Export `TOKEN` (the access token), `TENANT_ID` (your authorized UUID) and `API=http://127.0.0.1:8080`. A valid OWNER/ADMIN membership is required for creation/upload. MEMBER may read/search. The following subshell creates a clearly named DRAFT test agent and one TXT document; both persist because deletion is outside this task. It uses only synthetic text and stops on errors:

```sh
bash <<'SH'
set -eu
: "${API:?Set API}" "${TENANT_ID:?Set TENANT_ID}" "${TOKEN:?Log in and export TOKEN}"
AGENT_ID=$(curl --fail-with-body -sS "$API/api/v1/tenants/$TENANT_ID/agents" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Knowledge sample","systemInstructions":"Configuration only; no execution."}' |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
SAMPLE=$(mktemp /tmp/eaap-sample-XXXXXX.txt)
trap 'rm -f "$SAMPLE"' EXIT
printf 'Admission requires a passport and academic transcripts.\nLes candidats doivent fournir un passeport.\n' > "$SAMPLE"
DOCUMENT_ID=$(curl --fail-with-body -sS "$API/api/v1/tenants/$TENANT_ID/knowledge/documents" \
  -H "Authorization: Bearer $TOKEN" -F "agentId=$AGENT_ID" \
  -F "file=@$SAMPLE;type=text/plain;filename=admissions.txt" |
  python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["status"]=="PENDING"; print(d["id"])')
echo "Agent: $AGENT_ID; document: $DOCUMENT_ID"
READY=false
for attempt in $(seq 1 60); do
  STATUS=$(curl --fail-with-body -sS "$API/api/v1/tenants/$TENANT_ID/knowledge/documents/$DOCUMENT_ID" \
    -H "Authorization: Bearer $TOKEN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["status"])')
  if [ "$STATUS" = READY ]; then READY=true; break; fi
  if [ "$STATUS" = FAILED ]; then echo 'Processing failed; inspect metadata failureCode.'; exit 1; fi
  sleep 2
done
[ "$READY" = true ] || { echo 'Still processing; inspect document status.'; exit 1; }
curl --fail-with-body -sS "$API/api/v1/tenants/$TENANT_ID/agents/$AGENT_ID/knowledge/search" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"query":"Which documents are required for admission?","topK":5}' |
  python3 -m json.tool
SH
```

Search returns actual ranked passages with documentId/filename/chunkId/text/score and nullable sourcePage. TXT has no invented page number. PDF pages are mapped by per-page extraction. Cosine score is [-1,1], larger means closer; it is not answer confidence. Search only considers the accessible DRAFT agent's associated READY documents and produces no generated answer.

Other operations, replacing UUID variables with your actual IDs:

```sh
curl --fail-with-body -sS "$API/api/v1/tenants/$TENANT_ID/knowledge/documents?page=0&size=20" -H "Authorization: Bearer $TOKEN"
curl --fail-with-body -sS "$API/api/v1/tenants/$TENANT_ID/knowledge/documents/$DOCUMENT_ID" -H "Authorization: Bearer $TOKEN"
curl --fail-with-body -sS -X POST "$API/api/v1/tenants/$TENANT_ID/knowledge/documents/$DOCUMENT_ID/retry" -H "Authorization: Bearer $TOKEN"
curl --fail-with-body -sS -X POST "$API/api/v1/tenants/$TENANT_ID/knowledge/documents/$DOCUMENT_ID/agents/$AGENT_ID" -H "Authorization: Bearer $TOKEN"
curl --fail-with-body -sS -X DELETE "$API/api/v1/tenants/$TENANT_ID/knowledge/documents/$DOCUMENT_ID/agents/$AGENT_ID" -H "Authorization: Bearer $TOKEN"
```

The sample subshell prints IDs; export them in your parent shell before using later commands. Retry accepts FAILED only: success 202, unsupported/repeated concurrent transition 409. Association add/remove are idempotent; new associations require DRAFT. Archived agents cannot search (409). Cross-tenant/inaccessible IDs give tenant-local 404; no membership gives 403. Invalid payload/UUID/filter gives 400, oversized actual HTTP upload 413, missing/expired token 401, model busy/unavailable 503. Tokens expire after 15 minutes.

## Failures, restart and verification

Sources and PENDING work survive restarts. One worker polls every three seconds; processing leases last two minutes and renew before each embedding batch. Expired PROCESSING jobs are reclaimed with a new fencing token; old workers cannot publish. READY and chunk insertion commit together. Partial embeddings never become searchable. Transient model failures become FAILED with a safe code and need explicit retry; database outages leave persisted work discoverable. Only the narrow fixed dispatcher functions discover/claim metadata; normal runtime credentials and tenant RLS protect source/chunk work.

Use bounded logs without DEBUG/SQL parameter tracing:

```sh
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml logs --tail=100 control-plane agent-runtime
```

Never paste tokens, sources, vectors or environment dumps into diagnostics. FAILED metadata contains safe codes; do not enable PDF parser or database failed-row/parameter dumps to diagnose private documents. Preserve runtime logging presets when reprovisioning existing volumes.

Normal automated suites are offline with respect to model artifacts and use disposable PostgreSQL plus explicit test-only deterministic embedding adapters:

```sh
(cd services/control-plane && ./mvnw -B -ntp verify)
(cd services/agent-runtime && uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked pytest)
(cd apps/web && npm run lint && npm run typecheck && npm test && npm run build)
```

The separate real CPU model smoke command above requires prepared assets; it never downloads them itself. Deterministic vectors establish pgvector ranking/isolation, not model semantic quality. No paid calls, OCR, generated answers, source deletion, frontend connectivity or agent execution are provided.
