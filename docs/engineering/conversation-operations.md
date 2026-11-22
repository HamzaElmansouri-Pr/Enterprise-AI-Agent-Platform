# Conversation operations

Follow [ADR 0009](../architecture/decisions/0009-secure-agent-conversations.md), [authentication](authentication-operations.md), [knowledge](knowledge-operations.md), [execution](agent-execution-operations.md) and [tenant access](tenant-access-operations.md). Conversations are private to their creator, including from tenant managers. These are development procedures, not production retention/compliance or deployment approval.

## Configure and migrate

Use the existing four-service Compose project, protected `.env`, dedicated signing files, existing account/tenant and prepared local embedding model. No new secret, dependency or service is introduced. New conversations require an accessible DRAFT agent. The default stack has no configured hosted provider. Creating/listing/renaming/closing needs no model call; sending uses existing tenant quota even when retrieval/provider fails.

With Java21, Python3.12/uv, Node22 and modern Compose/Buildx, `./dev.sh up` rebuilds the local stack and applies pending migrations through V8 using the offline migration profile. For a manually managed signed/knowledge-enabled stack, back up first, retain existing distinct `.env` credentials and configured signing/model directories, then run from the repository root:

```sh
docker compose build control-plane agent-runtime web
docker compose up -d --wait postgres
# Export MIGRATION_DB_PASSWORD from your protected local environment.
docker compose run --rm --no-deps -e MIGRATION_DB_PASSWORD \
  control-plane --spring.profiles.active=migration
# Repeat the migration command: expect V8 validated and no migration necessary.
docker compose -f compose.yaml -f compose.auth.yaml \
  -f compose.knowledge.yaml -f compose.execution.yaml up -d --wait
```

Knowledge/execution overlays require their existing variables and model setup; follow their guides rather than supplying fake production secrets or a provider URL override. Keep provider credentials only in the runtime. Runtime DB credentials cannot migrate. Do not edit V1–V7, delete Flyway history, reset development data or disable RLS to fix an upgrade. V8 adds run composite uniqueness, tables, indexes, invoker triggers and policies; table scans/DDL locks require an operator-reviewed window at production scale. Recovery is a reviewed forward migration or protected backup restore.

## Console walkthrough

1. Sign in at http://127.0.0.1:4173 and verify your tenant UUID. Open **Conversations**, choose the agent and create a bounded title. At most20 conversations per user/tenant exist, including closed ones; creation calls no provider.
2. Enter a question of at most1000characters. Review the existing explicit provider-cost notice and confirm. Requests share the existing20-admission tenant daily budget and two process slots with ordinary answers and generated proposals.
3. The view shows persisted user/assistant bubbles, execution state and exact stored citations. A failed turn has no successful assistant answer; inspect its safe failure code/actual usage in the execution result. Unknown usage is unavailable, not zero.
4. Ask a follow-up. The most recent six successful complete pairs, totaling at most8000Unicode characters, may reach the provider together with current authorized evidence. Failed/pending pairs and other conversations are excluded. No full-history replay or automatic model retry occurs.
5. Pages contain20 messages in stable chronological order: **Previous** reads earlier messages; **Next** reads later messages. Status retrieval is explicit; there is no unlimited polling. **Check turn outcome** reads the associated request even after reauthentication.
6. Rename the OPEN conversation or confirm terminal **Close conversation**. CLOSED is read-only and cannot reopen. Agent archival rejects new conversations/messages but preserves readable history; metadata rename/close on an existing OPEN conversation remains available.
7. Sign out and sign in again. Select the same tenant/agent and open the conversation; messages remain in PostgreSQL. Other users, including OWNER/ADMIN, cannot read it. Membership revocation blocks future tenant access but retains records.

Invitation/login tokens and passwords are never put into browser storage, cookies or URLs. Pending request IDs use session memory and authorized API paths, without storage or navigation-URL persistence. Pending IDs are retained in console memory across feature navigation; logout/tenant switch destroys that scope. Unresolved messages can still be found through persisted message requestId/sourceRunId metadata after signing in again. Customer content renders as ordinary text, not HTML.

## Terminal acceptance without jq

Use the `request` and `login` Bash helpers from [tenant access operations](tenant-access-operations.md), which read credentials through protected stdin/environment. Authenticate, set your existing `TENANT_ID`, and select an accessible DRAFT agent. Do not record private conversation bodies in shared terminal logs.

```bash
read -r -p 'Existing DRAFT agent UUID: ' AGENT_ID
BASE="/tenants/$TENANT_ID/agents/$AGENT_ID/conversations"
CONVERSATION=$(printf '%s' '{"title":"Admissions questions"}' | request POST "$BASE")
CONVERSATION_ID=$(printf '%s' "$CONVERSATION" |
  python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
unset CONVERSATION
request GET "$BASE?page=0&size=20" </dev/null
request GET "$BASE/$CONVERSATION_ID" </dev/null

REQUEST_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
export REQUEST_ID
read -r -p 'Question: ' QUESTION
export QUESTION
TURN=$(python3 -c 'import os,json; print(json.dumps({"message":os.environ["QUESTION"],"requestId":os.environ["REQUEST_ID"]}))' |
  request POST "$BASE/$CONVERSATION_ID/messages")
printf '%s' "$TURN" | python3 -m json.tool
```

A reserved turn returns HTTP200 with `status` RUNNING/SUCCEEDED/FAILED and `result`. **HTTP200 alone does not mean the model succeeded.** `result` contains runId/answer/citations/failureCode/modelId/actual usage. FAILED has null answer. An ambiguous network failure must be resolved using the original request ID, not a new POST or UUID:

```bash
request GET "$BASE/$CONVERSATION_ID/requests/$REQUEST_ID" </dev/null
request GET "$BASE/$CONVERSATION_ID/messages?page=0&size=20" </dev/null
```

For an explicit idempotency test only, replay the exact same question and request ID. It must return the same durable terminal result and runId without another provider call, quota admission or message pair. Same ID with a different question returns409. Do not turn this example into a retry loop:

```bash
python3 -c 'import os,json; print(json.dumps({"message":os.environ["QUESTION"],"requestId":os.environ["REQUEST_ID"]}))' |
  request POST "$BASE/$CONVERSATION_ID/messages"
```

Each genuinely new logical question requires a new UUID. The maximum is100 turns per conversation, including failed attempts. Lifecycle operations are separate explicit actions:

```bash
printf '%s' '{"title":"Reviewed admissions questions"}' |
  request PUT "$BASE/$CONVERSATION_ID"
request POST "$BASE/$CONVERSATION_ID/close" </dev/null
unset TURN QUESTION REQUEST_ID
```

## Test without a hosted provider

Keep OPENAI_API_KEY and EXECUTION_MODEL empty. Upload a synthetic TXT/PDF and wait for READY with an association to the DRAFT agent, following knowledge operations. A question with authorized evidence reaches Python and produces durable FAILED/MODEL_UNAVAILABLE; replay remains that same failure even if provider configuration changes later. Without usable associated READY evidence, the outcome is FAILED/INSUFFICIENT_KNOWLEDGE before hosted inference. Both consume one admitted logical turn, not repeated admissions on replay. Local embeddings require the prepared model but no paid provider.

Default deterministic tests use test adapters and mocked HTTP; none require a paid key:

```sh
(cd services/control-plane && ./mvnw -B -ntp verify)
(cd services/agent-runtime && uv run --frozen ruff check . && uv run --frozen ruff format --check . && uv run --frozen pytest)
(cd apps/web && npm run lint && npm run typecheck && npm test && npm run build)
```

Optional real hosted testing requires explicit authorization for the cost and provider egress. Configure only the existing approved adapter/model as described in execution operations. Send one deliberate synthetic question, inspect real usage and citations, and distinguish application behavior from model quality. No real hosted quality/cost claim was verified in this delivery. Never add a production fake-provider switch or arbitrary provider URL.

## Failure, recovery and troubleshooting

- 400: exact JSON fields, title/message Unicode bounds, request UUID, page1–100 and16KiB API body. Do not supply userId/tenantId/agentId/role authority fields.
- 401: invalid/expired token or disabled user; sign in normally. Reauthentication does not erase persisted history.
- 403: current membership is missing; do not bypass it with a role header or another tenant UUID.
- 404: absent/inaccessible conversation/request; managers also receive404 for another creator's conversation. A missing status after transport ambiguity does not prove an earlier request could never arrive; the UI keeps its request ID. Definite rejected sends release their draft, without automatic retry.
- 409: CLOSED/archived/active turn, title transition, changed question for an existing request ID, conversation cap or100-turn cap. Only one active run per conversation is allowed. Inspect existing state rather than retrying mutations automatically.
- 429: shared two-slot process budget or20 admitted attempts per tenant/UTC day. No conversation-specific unlimited quota exists. A rejected pre-reservation admission has no durable turn; a later outcome GET can confirm that.
- FAILED/PROVIDER_TIMEOUT or another safe model failure: the provider may have processed/billed a request. No answer is invented, and the original UUID is never automatically dispatched again.
- RUNNING after interruption: authorized outcome/message reads or the next send recover runs older than180seconds to FAILED/EXECUTION_INTERRUPTED. Restarting a process preserves reservation identity and never restarts inference automatically. Late results cannot overwrite a terminal/recovered run. No background scheduler is introduced.
- 500/database uncertainty: retain requestId and safe X-Request-ID, inspect the original outcome after service recovery, and do not claim success from an unpersisted response. Revocation during inference can prevent final persistence; later authorized access can recover a stale run.

Do not enable SQL parameter/body/JWT or provider payload dumps. Service logs use operational IDs/status/usage/duration without private content. Database role logging protections must remain in place. `app.user_id` and `app.tenant_id` are trusted application assertions, not cryptographic database authentication; arbitrary runtime SQL access is outside the guarantee.

## Privacy and isolated verification

Questions, successful answers, request/message/run identity, ownership, citation snapshots, actual usage and failures persist in PostgreSQL and backups. Only the current enabled creator with membership can read through these APIs. Archived/closed/revoked records are retained; no deletion/retention API exists yet. Protect backups and privileged database access. Configured inference receives the bounded selected customer content. No training, automatic learning, shared memory or third-party export beyond configured inference is implemented; production retention/deletion/compliance remains unresolved.

Automated acceptance must use a distinct Compose project/volume and unused loopback ports with synthetic accounts. Never reset, migrate, reseed or reuse normal development data for automated tests. The delivery harness used project `eaap-010-verification`, control-plane18100/web14193, unique verification image names, the actual local embedding model, and a temporary test-only SDK HTTP mock mount. It verified browser sends/follow-up/citations, idempotent replay, logout/login, private isolation, closure, actual control-plane restart and cleanup without a paid call. See [EAAP-010 evidence](../tasks/EAAP-010.md) for completed results and limitations.
