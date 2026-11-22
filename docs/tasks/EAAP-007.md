# EAAP-007 — Trusted Tool Gateway and human approval

Implements the approved EAAP-007 task on `feat/eaap-007-tool-gateway-approvals`. Started from clean latest master `f1ebe4b` after confirming EAAP-006 PR #9 was merged. Pending independent review. Do not merge or start EAAP-008.

## Delivery and significant files

- `tool/domain`: strict fixed `ToolPayload`/arguments, proposal lifecycle/digest preconditions, read-only follow-up task and safe errors. Canonical SHA-256 binds proposal/tenant/agent/creator/tool/version/exact fields, without claiming a signature.
- `tool/application`: `ToolGateway` authorizes live membership, separates proposal/decision/execution, enforces different-human approval and dispatches via a one-entry `ToolRegistry`. Ports cover specific storage/generation boundaries.
- `tool/infrastructure`: `JdbcProposalStore` performs prepared, transaction-bound tenant SQL and the real `createFollowup` handler. Savepoints recover failed handler mutations; claim/effect/terminal commit atomically. `HttpProposalRuntime` independently validates bounded internal output; wiring selects only explicit production implementations.
- `tool/api` and exact security route additions: strict JSON/no authority fields, manager/manual/decision/execute paths, MEMBER creator visibility, bounded task/proposal pages and private error/response handling. SQL/JSON exception details are sanitized before framework debug logging.
- V6 adds three forced-RLS tables, composite ownership/digest/result FKs, task source uniqueness, named runtime updates, immutable/decision/transition/effect/commit validation triggers. No new privileged dispatcher or DELETE/DDL grants. V1–V5 and accepted tenant capability/helper/transaction code are unchanged.
- `execution/application/ExecutionAdmission` is now shared by ordinary Q&A and proposal generation; `AgentRuns` and wiring use it without changing the Q&A contract or quotas.
- Python `proposals/contracts.py` and `graph.py` add strict typed inert proposal state/output and a separate four-node actual graph. The existing provider's private request path supports only two server-chosen schemas. The existing execution service's slots, secret, tracing suppression and deadlines serve both paths; the internal router adds `/internal/tool-proposals`. No new provider/dependency/model/framework/secret/service is introduced.
- New PostgreSQL/HTTP and Python SDK/graph tests; existing migration-history assertions advance to V6 and test fixture truncation includes new dependent tables. CI still runs all suites with no paid calls.
- [ADR 0006](../architecture/decisions/0006-tool-gateway-and-human-approval.md), [operations](../engineering/tool-approval-operations.md), README/architecture/task indexes.

## API and authorization

Under `/api/v1/tenants/{tenantId}`:

- POST `/agents/{agentId}/tool-proposals`: OWNER/ADMIN, exact toolName/arguments/rationale,201/PENDING, no model/effect.
- POST `/agents/{agentId}/tool-proposals/generate`: all roles, strict intent<=1,000, DRAFT agent, bounded model generation and independently validated201/PENDING only.
- GET `/tool-proposals` and `/{proposalId}`: managers see tenant proposals; MEMBER sees only own. Exact payload/digest/creator/reviewer/executor/result metadata is returned.
- POST `/{proposalId}/approve`: other OWNER/ADMIN, expectedPayloadDigest required, no task.
- POST `/{proposalId}/reject`: manager, expected digest plus short reason, no task. Self-rejection is allowed; self-approval is not.
- POST `/{proposalId}/execute`: current manager, no body, approved identity/digest/tool/version/schema checked, one actual OPEN task or immutable success replay.
- GET `/admissions/followup-tasks`: all current members, bounded tenant page.

Public bodies<=16KiB, title<=120 safe Unicode code points, description<=1,000, rationale/reason<=500, priority LOW/NORMAL/HIGH, pagination default20/max100/offset<=10,000. Unexpected fields, authority, URL destinations in tool arguments, wrong types/Unicode, malformed JSON and invalid transitions are rejected. Fields are inert parameterized SQL data, never executable instructions. There is no argument-edit or combined approval/execute API.

## Persistence, audit, failure and concurrency

PENDING→APPROVED/REJECTED; APPROVED→EXECUTING→SUCCEEDED/FAILED. Runtime cannot change immutable identity/arguments/digest/decision/task rows or overwrite terminal proposals. Review records bind exact digest/creator/approver/decision/time/correlation; self-approval and cross-tenant references are rejected by DB constraints as well as application logic. Proposal/result metadata identifies original executor, timing/failure and task.

Row locking and conditional updates serialize competing decisions and executions. Restrictive UPDATE RLS remains; terminal replay reads immutable terminal rows without update locking. Successful execute replay200 returns the same task/original executor. Repeated decisions and invalid/REJECTED/FAILED execution409. A unique tenant/source-proposal key enforces one business task, with exact-argument insert validation and a same-proposal result FK.

Handler failures roll back the task at a savepoint and commit FAILED/TOOL_EXECUTION_FAILED; SQL-aborted transactions are explicitly tested. Failure before commit rolls back task+claim and returns sanitized500, leaving APPROVED for explicit later execution. A lost commit acknowledgment can instead leave a committed terminal outcome; inspect GET and use immutable success replay. Process interruption similarly rolls back. A deferred constraint rejects a committed stranded EXECUTING row; restart uses durable state, not an in-memory idempotency lock. These semantics do not claim exactly-once future external HTTP effects.

The Q&A/proposal paid allowance is shared:20 attempts per tenant/UTC day and two nonblocking Spring slots, plus the same two Python slots. Admitted failures count. No DB transaction is held during the provider call. Existing model identity, key/URL,800-token limit,128KiB internal body,24,000-character prompt budget and provider/graph/HTTP deadlines remain. Manual creation is free of model calls/quota but still requires manager authorization and a separate reviewer. No production fake adapter or automatic approval exists.

## Validation and actual evidence

Commands executed with JDK21 and Docker/Testcontainers (host tool/cache paths were under `/tmp`):

```sh
(cd services/control-plane && ./mvnw -B -ntp verify)
(cd services/agent-runtime && uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked pytest -q)
(cd apps/web && npm run lint && npm run typecheck && npm test && npm run build)
git diff --check
```

Tests cover all accepted regressions plus valid/invalid manual/generation schemas, roles/enabled/expired/forged/current membership, creator filtering, self-approval, exact digest, safe private errors/logs, real task fields, no side effects on creation/approval/rejection, concurrent approvals/rejection/execution, duplicate/source uniqueness, FK/RLS/grant/terminal immutability, handler/savepoint/terminal rollback, process restart, corrupted approval digest and shared paid admission. Python exercises actual graph order, one SDK request, typed strict output, injection/unknown tool/argument/authority rejection, shared slots, tracing suppression and timeouts through deterministic/mocked providers.

A disposable four-service Compose script under `/tmp` built/provisioned/migrated/repeated/bootstrap/started the platform, generated a synthetic reviewer's Argon2 hash with the existing Java encoder, and inserted the second ADMIN account through the existing offline identity operator. Two real logins exercised manual PENDING → different-user review/APPROVED → real task/SUCCEEDED, self-approval403, replay and an actual Spring process restart with the same single task. Evidence IDs (disposable data, now removed): proposal `a51aa8ce-464e-41e5-bde3-f73501f0b2cc`, task `f9170dde-9d4c-448c-bf13-833050d03e87`.

The same isolated stack verified real local embeddings/pgvector retrieval and existing Q&A, then mounted a **test-only SDK transport outside the repository** for the actual proposal graph. Generated proposal `99ba0856-c382-4a07-8b91-e548842e0308` remained PENDING with no additional task. Hosted credentials were explicitly cleared until the test-only mock, so no paid request occurred. This validates the pipeline/contracts, not real hosted generation quality.

Python memory observed after real embedding plus both graph/SDK-mock paths: **859.6MiB /1.5GiB**, no OOM. Existing SQL pool/transaction/time bounds remain; no new infrastructure or generative local model is loaded. Disposable containers/volume/keys/passwords/hash/test transport were removed. The normal development stack/data/accounts were not migrated, restarted or provisioned. Final local results: **205 Java tests** (71 unit/security +134 integration), **93 Python tests**, **1 frontend test**, lint/format/types/build and both final service images passed. Actual commit/PR and GitHub CI status are reported in the delivery response/associated PR. No dedicated vulnerability scanner or production throughput benchmark is claimed.

## Operational procedure and remaining review

[Tool approval operations](../engineering/tool-approval-operations.md) contains exact startup/V6 migration, explicit offline second-reviewer provisioning with the existing operator/encoder, two hidden-password logins, manual submission, exact-payload review/digest approval, separate execute/replay and task verification commands. No public identity/role management or automatic second account is introduced. A one-user tenant remains pending until an authorized second reviewer exists.

Only one internal SQL tool is implemented. Provider quality/account access were not tested with a paid call. Prompt injection and valid JSON do not establish intent correctness/authorization; humans must review. Shared-secret networking is not production workload identity; limits are per process, manual-write abuse/retention policies and customer-content protection require production review. Future external-tool dispatch/reconciliation/idempotency requires another approved design. No CRM/email/SMS/browser/shell/SQL tool, dynamic plugin, tool chaining, approval UI, memory, broker, billing or production deployment is added.
