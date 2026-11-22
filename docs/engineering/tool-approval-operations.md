# Local tool proposal, human approval and execution

Follow [ADR 0006](../architecture/decisions/0006-tool-gateway-and-human-approval.md). The only operation is `admissions.create_followup_task`. Proposal creation and approval never create a task. Self-approval is forbidden even for OWNER. No paid model is required for the manual acceptance path.

## Startup and migration

Prerequisites: Docker Engine/Compose, configured `.env`, PostgreSQL roles/pgvector from [database](database-operations.md) and [knowledge operations](knowledge-operations.md), plus existing authentication keys/accounts from [authentication operations](authentication-operations.md). Preserve the existing volume. Back up/review before V6: FK/index/trigger DDL can lock metadata. Failed migration rolls back; applied defects require a new forward migration, not edits/repair/drop-volume.

From repository root:

```sh
./dev.sh init
docker compose up -d --wait postgres
docker compose build control-plane agent-runtime
docker compose -f compose.yaml -f compose.migration.yaml run --rm --no-deps control-plane
# Four services, auth enabled. Manual gateway tests require no provider or embedding model.
docker compose -f compose.yaml -f compose.auth.yaml -f compose.execution.yaml config --quiet
docker compose -f compose.yaml -f compose.auth.yaml -f compose.execution.yaml up -d --wait
```

Configure a dedicated execution secret as described in [execution operations](agent-execution-operations.md) before using that overlay. Its Python port stays internal; the provider key may remain empty. For the full knowledge-enabled stack, use the existing `compose.knowledge.yaml` before `compose.execution.yaml` and prepare its model assets as already documented. No new service, secret, framework or provider is introduced here. V1–V5 are unchanged; migration uses only the offline migration credential.

## Two authorized accounts

Use two existing enabled OWNER/ADMIN accounts in the same tenant when available. One proposes, a different UUID approves; either current manager may execute. A MEMBER may generate a proposal and view only their own, but cannot manually submit/approve/execute. All tenant members may view created tasks. If only one account exists, proposals remain pending until an operator explicitly provisions a second authorized reviewer. There is no public signup/role endpoint or bypass.

The following optional procedure is an **explicit privileged operator action**, not automatic application startup. Review the tenant/email/role before running it. It uses the existing insert-only `eaap_identity_operator`; it cannot change existing accounts or memberships. Choose a genuinely separate authorized reviewer account. Do not paste real credentials into documentation, Git, logs or shell history.

Host prerequisites for generating the password hash: JDK21 and the existing Maven wrapper. The temporary Java program calls the platform's accepted Argon2 encoder and validates its accepted email; no new password library is selected. Run in Bash from repository root:

```bash
read -r -p 'Existing tenant UUID: ' TENANT_ID
SECOND_USER_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
read -r -p 'Second reviewer email: ' SECOND_EMAIL
SECOND_EMAIL=$(printf '%s' "$SECOND_EMAIL" | python3 -c 'import sys; print(sys.stdin.read().strip().lower())')
read -r -s -p 'Second reviewer password (16–128 characters, hidden): ' SECOND_PASSWORD
printf '\n'
export TENANT_ID SECOND_USER_ID SECOND_EMAIL SECOND_PASSWORD
HASH_TMP=$(mktemp -d /tmp/eaap-reviewer-hash.XXXXXX)
(cd services/control-plane && ./mvnw -B -ntp -DskipTests package dependency:build-classpath -Dmdep.includeScope=runtime -Dmdep.outputFile="$HASH_TMP/classpath")
cat > "$HASH_TMP/HashPassword.java" <<'JAVA'
import com.elmacore.agentplatform.identity.infrastructure.ArgonPasswords;
import com.elmacore.agentplatform.identity.domain.Email;
class HashPassword {
    public static void main(String[] args) {
        String password=System.getenv("SECOND_PASSWORD");
        new Email(System.getenv("SECOND_EMAIL"));
        if(password==null || password.isBlank() || password.length()<16 || password.length()>128)
            throw new IllegalArgumentException("Password must contain 16–128 characters");
        System.out.println(new ArgonPasswords().encode(password));
    }
}
JAVA
SECOND_HASH=$(java -cp "services/control-plane/target/classes:$(cat "$HASH_TMP/classpath")" "$HASH_TMP/HashPassword.java" |
  python3 -c 'import sys; rows=[r.strip() for r in sys.stdin if r.startswith("$argon2id$")]; assert len(rows)==1; print(rows[0])')
unset SECOND_PASSWORD
export SECOND_HASH
```

Stop if hashing/build validation fails. The next command deliberately grants the new account ADMIN membership in the selected tenant. It fails rather than overwriting any existing user/membership:

```bash
: "${TENANT_ID:?Set tenant}" "${SECOND_USER_ID:?Set reviewer UUID}" "${SECOND_EMAIL:?Set email}" "${SECOND_HASH:?Generate hash first}"
docker compose exec -T -e TENANT_ID -e SECOND_USER_ID -e SECOND_EMAIL -e SECOND_HASH postgres \
  sh -c 'PGPASSWORD="$IDENTITY_OPERATOR_DB_PASSWORD" exec psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U eaap_identity_operator -d "$POSTGRES_DB"' <<'SQL'
\getenv tenant_id TENANT_ID
\getenv reviewer_id SECOND_USER_ID
\getenv reviewer_email SECOND_EMAIL
\getenv reviewer_hash SECOND_HASH
BEGIN;
SELECT set_config('app.tenant_id', :'tenant_id', true);
INSERT INTO platform.users(id,email,password_hash)
VALUES (:'reviewer_id'::uuid, :'reviewer_email', :'reviewer_hash');
INSERT INTO platform.tenant_memberships(user_id,tenant_id,role)
VALUES (:'reviewer_id'::uuid, :'tenant_id'::uuid, 'ADMIN');
COMMIT;
SQL
unset SECOND_HASH
rm -rf -- "$HASH_TMP"
unset HASH_TMP
```

The tenant must already exist and the operator password must match the provisioned role. Preserve the database's terse/no-parameter logging controls. Do not share resolved Compose environments, hash material or operator logs containing secrets. There is no automated account deletion/reset in this task.

## Exact offline acceptance procedure

Use an existing DRAFT agent in that tenant. [Knowledge operations](knowledge-operations.md) includes agent creation; no documents are needed for this tool proposal path. Log in with each account; passwords are read privately and tokens stay in shell variables:

```bash
export API='http://127.0.0.1:8080'
read -r -p 'Authorized tenant UUID: ' TENANT_ID
read -r -p 'DRAFT agent UUID: ' AGENT_ID
export TENANT_ID AGENT_ID
login_token() {
  local email password payload response
  read -r -p 'Login email: ' email
  read -r -s -p 'Password (hidden): ' password
  printf '\n' >&2
  payload=$(printf '%s\n%s\n' "$email" "$password" | python3 -c 'import json,sys; print(json.dumps({"email":sys.stdin.readline().rstrip("\n"),"password":sys.stdin.readline().rstrip("\n")}))')
  response=$(curl --fail-with-body -sS "$API/api/v1/auth/login" -H 'Content-Type: application/json' --data-binary "$payload") || return
  printf '%s' "$response" | python3 -c 'import json,sys; token=json.load(sys.stdin)["accessToken"]; assert isinstance(token,str) and token; print(token)'
}
echo 'Log in as proposer OWNER/ADMIN:'
TOKEN1=$(login_token)
echo 'Log in as a different reviewer OWNER/ADMIN:'
TOKEN2=$(login_token)
: "${TOKEN1:?Login failed}" "${TOKEN2:?Login failed}"
```

Create the manual proposal; it is inert and makes no model call:

```bash
PROPOSAL_FILE=$(mktemp /tmp/eaap-proposal.XXXXXX)
curl --fail-with-body -sS -o "$PROPOSAL_FILE" \
  -X POST "$API/api/v1/tenants/$TENANT_ID/agents/$AGENT_ID/tool-proposals" \
  -H "Authorization: Bearer $TOKEN1" -H 'Content-Type: application/json' \
  --data-binary '{"toolName":"admissions.create_followup_task","arguments":{"title":"Review admission documents","description":"Check whether the applicant submitted all required documents.","priority":"NORMAL"},"rationale":"Staff should review the submitted documents."}'
python3 -m json.tool "$PROPOSAL_FILE"
PROPOSAL_ID=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$PROPOSAL_FILE")
```

Expected201/PENDING_APPROVAL, taskId null, no task effect. The different reviewer must retrieve and review the exact tool/version/arguments/rationale and creator before deciding:

```bash
curl --fail-with-body -sS -o "$PROPOSAL_FILE" \
  "$API/api/v1/tenants/$TENANT_ID/tool-proposals/$PROPOSAL_ID" \
  -H "Authorization: Bearer $TOKEN2"
python3 -m json.tool "$PROPOSAL_FILE"
DIGEST=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["payloadDigest"])' "$PROPOSAL_FILE")
# Run only after that reviewer explicitly agrees to this exact displayed payload.
curl --fail-with-body -sS -X POST \
  "$API/api/v1/tenants/$TENANT_ID/tool-proposals/$PROPOSAL_ID/approve" \
  -H "Authorization: Bearer $TOKEN2" -H 'Content-Type: application/json' \
  --data-binary "{\"expectedPayloadDigest\":\"$DIGEST\"}" | python3 -m json.tool
```

Expected APPROVED, taskId still null. TOKEN1 self-approval would be403. The separate execute request now performs the approved SQL operation:

```bash
curl --fail-with-body -sS -X POST \
  "$API/api/v1/tenants/$TENANT_ID/tool-proposals/$PROPOSAL_ID/execute" \
  -H "Authorization: Bearer $TOKEN1" | python3 -m json.tool
# Deliberate replay: expect the same taskId and original executor, no additional task.
curl --fail-with-body -sS -X POST \
  "$API/api/v1/tenants/$TENANT_ID/tool-proposals/$PROPOSAL_ID/execute" \
  -H "Authorization: Bearer $TOKEN2" | python3 -m json.tool
TASKS_FILE=$(mktemp /tmp/eaap-tasks.XXXXXX)
curl --fail-with-body -sS -o "$TASKS_FILE" \
  "$API/api/v1/tenants/$TENANT_ID/admissions/followup-tasks?page=0&size=100" \
  -H "Authorization: Bearer $TOKEN1"
python3 - "$TASKS_FILE" "$PROPOSAL_ID" <<'PY'
import json,sys
rows=json.load(open(sys.argv[1]))['items']
matches=[row for row in rows if row['sourceProposalId']==sys.argv[2]]
assert len(matches)==1, 'Expected one task for this proposal in the current page'
assert matches[0]['status']=='OPEN'
print(json.dumps(matches[0], indent=2))
PY
rm -f -- "$PROPOSAL_FILE" "$TASKS_FILE"
unset TOKEN1 TOKEN2 DIGEST PROPOSAL_FILE TASKS_FILE
```

Success is SUCCEEDED with one real task containing the exact approved fields. The task list is paginated; under a busy tenant, page through it if the new task falls outside the first100. The database's unique source-proposal key enforces the global invariant independently of this page check.

To reject a fresh pending proposal instead, send TOKEN2 to POST `/tool-proposals/{id}/reject` with `{"expectedPayloadDigest":"THE_REVIEWED_DIGEST","reason":"Explain why it is not needed."}`. Expected REJECTED and no task. Repeated approve/reject or execute of REJECTED/FAILED gives409; there is no reset/retry endpoint. A mismatched digest409 leaves the pending proposal unchanged. No endpoint edits a proposal or combines approval/execution.

## Optional AI generation and failure diagnosis

After explicit operator opt-in using the existing [provider configuration](agent-execution-operations.md), any current tenant role may POST `/agents/{agentId}/tool-proposals/generate` with strict `{"intent":"Create a follow-up task to review admission documents."}`. This is potentially billable and consumes the same20-attempt UTC-day budget and two Spring/Python process slots used for ordinary Q&A. No key is needed for manual proposals; absent configuration returns503 and creates no proposal/action. No paid calls are made in CI/task validation. There is no production fake-provider switch.

The AI path uses server-loaded instructions and intent, not a second retrieval system or existing Q&A behavior. Runtime output is only untrusted proposal data; Spring independently validates it and persists PENDING. Prompt injection cannot provide a trusted role/tenant/approval. It may still affect suggested business content, so review the exact arguments; schema validity does not prove correctness.

Use X-Request-ID/correlation ID and proposalId to diagnose failures:

```sh
docker compose -f compose.yaml -f compose.auth.yaml -f compose.execution.yaml logs --tail=100 control-plane agent-runtime
```

Logs contain IDs/states/safe categories, not arguments, rationale, intent, full provider payloads, keys or JWTs. A handler failure rolls back its business effect and records FAILED/TOOL_EXECUTION_FAILED; execute returns the terminal representation. If the database cannot save the terminal outcome, it returns500 and rolls back task+claim to APPROVED. Inspect GET before making an explicit later attempt: a lost commit acknowledgment can leave a committed SUCCEEDED result even when the client received500; replay then returns that same task. A process crash similarly rolls back; no EXECUTING state may commit. This atomic recovery applies only to the internal SQL tool, not future external HTTP effects.

These are development boundaries: shared-secret networking is not mutual TLS/workload identity, concurrent paid limits are per process, and content retention/deletion plus authorized manual-write abuse controls require production review. No user-management UI, CRM/email/SMS, dynamic tools, general automation or production deployment is provided.

## Offline verification

From repository root, with JDK21/Python3.12/uv and Docker available:

```sh
(cd services/control-plane && ./mvnw -B -ntp verify)
(cd services/agent-runtime && uv sync --locked && uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked pytest)
(cd apps/web && npm ci && npm run lint && npm run typecheck && npm test && npm run build)
git diff --check
```

Tests use disposable PostgreSQL, generated test-only signing keys, deterministic model doubles and mock SDK HTTP. They verify roles/creator filtering/self-approval, immutable digest/decision, real task creation, conflicting decisions, duplicate execution, savepoint/outer rollback, restart, RLS/grants/FKs and paid-limit sharing. They never provision an account in your real tenant or make a paid model call.
