# Local controlled agent execution operations

Follow [ADR 0005](../architecture/decisions/0005-controlled-agent-execution.md). This is the four-service development foundation. There is no chat UI, memory, tool execution or automatic replay. Existing data must never be reset to run a test.

## Prepare configuration and migration

First configure authentication/tenant bootstrap using [authentication operations](authentication-operations.md), then prepare the fixed local embedding model and pgvector using [knowledge operations](knowledge-operations.md). Use Docker Compose with `!reset` support, Java 21, Python 3.12 and uv 0.13.9. Startup and all default tests need no hosted provider key.

From repository root, generate the dedicated execution secret in ignored `.env` without printing it. This preserves existing nonempty settings. Configure the key only if you want to enable paid execution:

```sh
./dev.sh init
python3 - <<'PY'
from pathlib import Path
import os, secrets, tempfile
path = Path('.env')
lines = path.read_text().splitlines()
values = dict(line.split('=', 1) for line in lines if '=' in line and not line.lstrip().startswith('#'))
updates = {
    'EXECUTION_INTERNAL_SECRET': values.get('EXECUTION_INTERNAL_SECRET') or secrets.token_urlsafe(48),
    'EXECUTION_MODEL': values.get('EXECUTION_MODEL') or '',
    'EXECUTION_MAX_OUTPUT_TOKENS': values.get('EXECUTION_MAX_OUTPUT_TOKENS') or '800',
    'EXECUTION_PROVIDER_TIMEOUT_SECONDS': values.get('EXECUTION_PROVIDER_TIMEOUT_SECONDS') or '30',
}
lines = [line for line in lines if line.split('=', 1)[0] not in updates]
fd, temporary = tempfile.mkstemp(prefix='.env.tmp.', dir='.')
with os.fdopen(fd, 'w') as stream:
    stream.write('\n'.join(lines + [f'{k}={v}' for k,v in updates.items()]) + '\n')
os.replace(temporary, path)
print('Dedicated execution configuration prepared; secret not displayed.')
PY
```

For an existing volume, back up/review DDL first using [database operations](database-operations.md). V5 creates run/quota tables and references existing agents/users/tenants, acquiring metadata locks. DDL is transactional; a failed V5 rolls back. Already applied defects require a new forward migration; never edit V1–V4, force repair, or delete the volume. Build/apply with the migration credential confined to a one-shot process:

```sh
docker compose up -d --wait postgres
docker compose build control-plane agent-runtime
# pgvector extension/roles must already be provisioned as documented for EAAP-005.
docker compose -f compose.yaml -f compose.migration.yaml run --rm --no-deps control-plane
```

Enable the opt-in overlay, initially without an API key:

```sh
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml -f compose.execution.yaml config --quiet
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml -f compose.execution.yaml up -d --wait
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml -f compose.execution.yaml ps
```

Only four services run. Python/PostgreSQL have no host-published port; requests go through Spring on `127.0.0.1:8080`. Python gets no database/JWT signing credentials; only it gets the provider key. Do not print full resolved Compose configuration or use `docker inspect` to share environment values. The shared secret protects only the trusted local Docker network, not production workload identity. An absent/invalid provider configuration yields 503 on execution and leaves health/ingestion/retrieval usable.

## Optional paid provider configuration

The only allowed snapshot is `gpt-5.4-mini-2026-03-17`, using OpenAI SDK 2.37.0 Responses structured outputs. Compatibility is documented by [OpenAI](https://developers.openai.com/api/docs/models/gpt-5.4-mini) and the installed SDK was tested with mocked HTTP responses. No real hosted invocation was performed for EAAP-006 validation. Verify account access and provider data controls before using private documents. Setting a key and executing the POST below makes a potentially billable call. Timeouts can still be billed.

To opt in intentionally, enter a key through a hidden terminal prompt rather than shell history (this also selects the exact approved snapshot):

```sh
python3 - <<'PY'
from pathlib import Path
from getpass import getpass
import os, tempfile
key = getpass('OpenAI API key (hidden): ').strip()
if not key or any(c.isspace() or c in "'\"$#\\" for c in key):
    raise SystemExit('Invalid API-key characters')
path = Path('.env')
lines = path.read_text().splitlines()
updates = {'OPENAI_API_KEY': key, 'EXECUTION_MODEL': 'gpt-5.4-mini-2026-03-17'}
lines = [line for line in lines if line.split('=', 1)[0] not in updates]
fd, temporary = tempfile.mkstemp(prefix='.env.tmp.', dir='.')
with os.fdopen(fd, 'w') as stream:
    stream.write('\n'.join(lines + [f'{k}={v}' for k,v in updates.items()]) + '\n')
os.replace(temporary, path)
print('Provider configuration saved privately; value not displayed.')
PY
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml -f compose.execution.yaml up -d --wait agent-runtime
```

Keep `.env` owner-readable only and untracked. Do not put provider/internal secrets in VITE variables, source, images or API bodies. No arbitrary provider URL, model override or production fake mode exists. Rotate a compromised key with its provider; rotate the internal secret on both services together. Disabling hosted execution requires clearing `OPENAI_API_KEY` and restarting Python.

## Exact API checks

Log in and set the authorized tenant and an existing DRAFT agent with associated READY knowledge. [Knowledge operations](knowledge-operations.md) includes exact agent creation, sample upload and status commands. The following login uses a hidden password; it requires only curl and Python 3, not jq. Run in Bash:

```bash
export API='http://127.0.0.1:8080'
read -r -p 'Authorized tenant UUID: ' TENANT_ID
read -r -p 'DRAFT agent UUID with READY knowledge: ' AGENT_ID
read -r -p 'Login email: ' EMAIL
read -r -s -p 'Password (hidden): ' PASSWORD
printf '\n'
PAYLOAD=$(printf '%s\n%s\n' "$EMAIL" "$PASSWORD" | python3 -c 'import json,sys; print(json.dumps({"email":sys.stdin.readline().rstrip("\n"),"password":sys.stdin.readline().rstrip("\n")}))')
TOKEN=''
if RESPONSE=$(curl --fail-with-body -sS "$API/api/v1/auth/login" \
  -H 'Content-Type: application/json' --data-binary "$PAYLOAD"); then
  TOKEN=$(printf '%s' "$RESPONSE" | python3 -c 'import json,sys; t=json.load(sys.stdin)["accessToken"]; assert isinstance(t,str) and t; print(t)')
fi
unset PASSWORD PAYLOAD RESPONSE
export TENANT_ID AGENT_ID TOKEN
```

This POST consumes one of the tenant's 20 UTC-day attempts when admitted. With valid hosted configuration it may be billable. Without configuration and with READY knowledge, expect sanitized 503, plus a persisted FAILED run ID:

```bash
: "${TOKEN:?Login first}" "${TENANT_ID:?Set tenant}" "${AGENT_ID:?Set agent}"
RESULT=$(mktemp /tmp/eaap-run-result.XXXXXX)
curl -sS -o "$RESULT" -w 'HTTP %{http_code}\n' \
  -X POST "$API/api/v1/tenants/$TENANT_ID/agents/$AGENT_ID/runs" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data-binary '{"message":"What documents are required for admission?","topK":5}'
python3 -m json.tool "$RESULT"
RUN_ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["runId"])' "$RESULT")
rm -f "$RESULT"
if [ -n "$RUN_ID" ] && [ "$RUN_ID" != None ]; then
  curl --fail-with-body -sS \
    "$API/api/v1/tenants/$TENANT_ID/agents/$AGENT_ID/runs/$RUN_ID" \
    -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
fi
```

Success: HTTP200, SUCCEEDED, actual answer and real document/chunk/filename/page citations, actual token usage or null, durationMs. No available knowledge or model abstention:422 `INSUFFICIENT_KNOWLEDGE`; no-context cases make no paid call. Other-tenant membership:403. Cross-tenant agent, other creator's run, unknown run:404. Archived start:409; creator may still inspect persisted status after archival. Unknown body fields, topK>6, blank/>1,000-character/invalid Unicode messages, duplicate JSON or query parameters:400. Disabled/expired authentication:401.

## Failure diagnosis and limits

Use the response `X-Request-ID` and runId. Inspect status with GET; do not repeat POST blindly, since it reserves another daily attempt and may repeat a billed request. There is no idempotent model replay claim. Concurrency429 means both Spring slots are occupied; daily429 resets at UTC midnight, not after the Retry-After hint alone. Provider timeout504/invalid or unavailable provider502/configuration503 are sanitized. Missing embedding assets/embedding contention may return503 before a run is created, but admitted quota still counts.

```sh
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml -f compose.execution.yaml logs --tail=100 control-plane agent-runtime
```

Execution telemetry includes run/correlation IDs, safe status/failure category, model, duration and known token counts. Do not enable SDK/HTTP body/SQL parameter logging; retain existing database terse/no-parameter logging presets. Never share keys, JWTs, private documents, raw requests or answers as diagnostic logs. PostgreSQL and provider exception text must remain private. If persistence fails after a provider response, the API returns failure rather than claiming success. A RUNNING run older than180 seconds is marked FAILED/EXECUTION_INTERRUPTED on its creator's next authorized start/status access for that agent; no replay occurs. Untouched runs await that access. Revoked membership prevents access until restored. No automatic global sweep is implemented.

Python remains capped at1.5GiB with the existing embedding model. Spring keeps its four-connection JDBC pool and two nonblocking execution slots; these are per-process limits, not distributed admission. Customer questions/answers and citation metadata are persisted; retention/deletion and production privacy/egress/TLS controls remain future requirements. `store=False` does not replace provider retention policy review.

## Offline regression suite

No API key, paid call or model download is required:

```sh
(cd services/agent-runtime && uv sync --locked && uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked pytest)
(cd services/control-plane && ./mvnw -B -ntp verify)
(cd apps/web && npm ci && npm run lint && npm run typecheck && npm test && npm run build)
```

Java tests require Docker/Testcontainers and use disposable databases, test-only keys, vectors and runtime responses. Python tests exercise the real StateGraph and OpenAI SDK parsing via mock HTTP transport; no fake adapter is shipped in normal runtime. This demonstrates contracts and protections, not real hosted answer quality or latency. Production and actual hosted acceptance need separate authorized review.
