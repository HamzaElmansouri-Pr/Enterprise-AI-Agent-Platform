# Tenant operations console

The console uses existing Spring APIs. Read [ADR 0007](../architecture/decisions/0007-tenant-operations-console.md). No provider/database/internal credentials belong in the browser. A real operator-provisioned account and tenant are required; there is no public signup or tenant discovery.

## Docker startup

From repository root, configure signing keys/issuer/audience/accounts using [authentication operations](authentication-operations.md). For an existing database, back up/review migrations and preserve its volume. Use the established [database](database-operations.md), [knowledge](knowledge-operations.md) and [execution](agent-execution-operations.md) setup for role/extension/model/secret configuration. Existing V1–V6 are unchanged.

```sh
./dev.sh init
# Existing dedicated local project: prepares roles, pgvector and migrations through V6.
./dev.sh up
# After configuring the auth keys and auth variables described above:
docker compose build web
# Management + manual approvals require no embedding model or hosted provider.
docker compose -f compose.yaml -f compose.auth.yaml config --quiet
docker compose -f compose.yaml -f compose.auth.yaml up -d --wait
```

Open `http://127.0.0.1:4173`. For real document processing/search and optional execution, prepare the pinned model directory and separate internal secrets using the linked operations guides, then run:

```sh
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml -f compose.execution.yaml config --quiet
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml -f compose.execution.yaml up -d --build --wait
```

This still has four services. PostgreSQL and Python internal endpoints remain unpublished with the knowledge/execution overlays. OPENAI_API_KEY and EXECUTION_MODEL may remain empty for offline management/manual approval; a question then reports unavailable after the existing admission/retrieval checks. Provider credentials, when explicitly configured by the operator, go only to Python. Python embeddings and Spring knowledge-worker configuration are needed for READY documents/search; they are not needed for login/agents/manual proposals/tasks. No automatic model calls occur on navigation.

The web container sets **server-only** `EAAP_WEB_CONTAINER=1`. Its Vite preview proxies `/api/v1/` to fixed `control-plane:8080` over Compose DNS. Old VITE_CONTROL_PLANE_API_URL/VITE_AGENT_RUNTIME_API_URL settings are unused and can be removed from ignored local environments. Do not put secrets in any VITE variable. Rebuild web after frontend/config changes. No CORS configuration is needed or changed.

Stop services while preserving data:

```sh
docker compose -f compose.yaml -f compose.auth.yaml -f compose.knowledge.yaml -f compose.execution.yaml down
```

## Host frontend development

Prerequisites: Node22.12+ within major22 and npm. Keep Spring running with authentication on loopback8080, for example using the Compose setup above. From repository root:

```sh
cd apps/web
npm ci
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

Open `http://127.0.0.1:5173`. Relative browser requests are proxied by Vite to fixed `http://127.0.0.1:8080`. Do not change the browser to call Spring or Python directly. No host Vite environment file is needed. Built preview can also run on the host using `npm run build` then `npm run preview -- --host 127.0.0.1 --port 4173 --strictPort`; do not collide with Compose's web port. This remains local development serving, not production hosting.

## Login and tenant selection

1. Enter your existing email/password at Sign in. Invalid credentials show a generic message.
2. Choose your organization by name and current role from the authenticated bounded directory. Opening it calls Spring's context endpoint and verifies live membership. Manual UUID entry remains a support fallback.
3. Switch tenant returns to fresh organization discovery and clears the previous workspace, pending requests and cost acknowledgement. Sign out clears the token and all tenant data. Reload requires signing in again. See [guided onboarding](onboarding-and-tenant-operations.md).
4. Sessions last at most15minutes. HTTP401 or local expiry returns to login with an expiry message. There are no refresh tokens, cookies or browser storage.

## Agent walkthrough

1. Open Agents. Use status filter/Previous/Next for bounded pages.
2. OWNER/ADMIN: Create agent, provide name, optional description and required system instructions, then Save configuration. The result is DRAFT.
3. View agent → Edit configuration to change editable fields. Archive agent opens a separate terminal-lifecycle confirmation.
4. MEMBER can view only. Archived configuration is read-only for all roles; there is no ACTIVE state or deletion.

## Knowledge walkthrough

1. Configure knowledge as above. OWNER/ADMIN opens Knowledge library, selects an accessible DRAFT agent and uploads one PDF or UTF-8 TXT, at most2MiB. PDF extraction additionally permits at most25pages and selectable text; existing parsing and20-document tenant limits apply.
2. Acceptance is PENDING, not READY. The list/detail refreshes at3seconds for up to60automatic checks while mounted, stopping at READY/FAILED. Use Refresh if the polling budget expires. Leaving the view or switching tenant cancels polling.
3. View document shows chunk count/safe failure category. An authorized manager can retry FAILED processing or add/remove agent associations. Removing an association does not delete the source. No document delete/download endpoint exists.
4. Open Knowledge search, select a DRAFT agent, enter a bounded query and Search knowledge. It shows actual passages/filenames/document IDs, page only when supplied and cosine similarity. Similarity is not factual confidence.

## Optional question answering

1. Open Ask an agent and choose a DRAFT agent with associated READY knowledge.
2. Enter one question<=1,000characters. Before the first execution in the session, acknowledge that configuration is required and a call may incur provider charges and consume the shared daily allowance.
3. Run question explicitly; there is no automatic retry. Success shows answer, citations, model, known usage and duration. Unknown usage is Unavailable.
4. Provider unconfigured/unavailable, insufficient knowledge, timeout and daily quota have clear error messages. If a runId was persisted, Retrieve outcome reads it without starting another attempt; alternatively enter an existing own run UUID below.
5. A timeout can still incur charges. Inspect durable status before an explicit new attempt. There is no conversation memory or automatic resubmission.

## Two-person approval walkthrough

Use **two distinct authorized accounts in the same tenant**. Provisioning is an explicit operator responsibility using the existing [tool approval operations](tool-approval-operations.md#two-authorized-accounts). Do not grant privileges through a public API or weaken self-approval. Two browser profiles/private windows let the sessions remain separate.

1. Creator OWNER/ADMIN: Tool proposals → New proposal → Manual. Select a DRAFT agent and enter title, description, LOW/NORMAL/HIGH priority and rationale. Submit unapproved proposal creates PENDING_APPROVAL only. No model call or task occurs.
2. Alternatively, all roles may choose AI generation, enter intent and explicitly confirm the possible paid call/shared budget. It only produces an unapproved proposal; the model cannot decide or execute.
3. Proposal detail shows exact immutable arguments, tool/version, creator, rationale and digest. The creator's Approve button is disabled. MEMBER sees only their own proposals and no management controls.
4. Different OWNER/ADMIN: sign in/select same tenant, open Tool proposals → Review proposal. Inspect exact arguments/digest, choose Approve proposal and confirm Approve only. The reviewed digest is submitted. Status becomes APPROVED; no task exists.
5. A manager separately chooses Execute approved action, reviews actual approved arguments and expected creation of one real OPEN task, then confirms Execute and create task. It displays SUCCEEDED/task UUID or a terminal safe failure.
6. View follow-up tasks shows the real task, priority, OPEN status, timestamp, creator and source proposal UUID. No task editing/deletion exists.
7. Rejection requires a short reason, confirms a terminal decision and never creates a task. A409 means status/digest changed: cancel, Refresh proposal and review again. The UI does not silently approve another version.

For an explicit idempotency check, use the existing authenticated terminal session from [tool approval operations](tool-approval-operations.md), with the actual proposal UUID. Do not paste tokens in URLs or browser storage:

```sh
curl -sS -X POST "$API/api/v1/tenants/$TENANT_ID/tool-proposals/$PROPOSAL_ID/execute" \
  -H "Authorization: Bearer $TOKEN1" | python3 -m json.tool
curl -sS "$API/api/v1/tenants/$TENANT_ID/admissions/followup-tasks?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN1" | python3 -m json.tool
```

A SUCCEEDED replay returns the original taskId and original executor; source-proposal uniqueness prevents duplicates. Count only the task for that sourceProposalId when the tenant already contains other tasks. The UI disables pending controls and does not automatically repeat mutations. A lost commit acknowledgment can mean the task committed despite an error: refresh proposal before any explicit retry.

## Verification and troubleshooting

```sh
(cd apps/web && npm run lint && npm run typecheck && npm test && npm run build)
(cd services/control-plane && ./mvnw -B -ntp verify)
(cd services/agent-runtime && uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked pytest -q)
git diff --check
```

Vitest uses test-only HTTP fixtures and real DOM interactions. No test provider is compiled into the interface. Java uses Docker/Testcontainers. Python HTTP tests need loopback sockets. Actual isolated browser/Docker evidence is in [EAAP-008](../tasks/EAAP-008.md); no paid model call is required.

- 502 from `/api`: inspect whether Spring is reachable at the fixed target. Host Vite requires8080; the container requires service DNS `control-plane:8080`. Do not add wildcard CORS or point the browser at internal Python.
- 503 login: check signing keys and database/operator setup with authentication operations. No default account/password is created by the web app.
- 401: sign in again.403: confirm current tenant membership/role with the operator; hiding UI controls is not an authorization bypass.404: record may be inaccessible.
- 409: lifecycle/digest changed; refresh/review.413: enforce2MiB.429: limits/shared daily allowance apply; no automatic replay.
- PENDING persists: check knowledge overlay/worker/model and safe logs. FAILED: inspect the safe code before an explicit retry.
- Hosted failure: model configuration/key may be absent. Tokens/model/usage are never invented. No live paid generation quality is claimed by offline tests.

Logs/errors must not contain JWTs, passwords, provider keys, private passages or questions. Response request UUIDs support safe diagnosis. Vite preview is development-only; production TLS/identity/hosting, content retention, browser/script compromise protections and distributed controls require architectural review.
