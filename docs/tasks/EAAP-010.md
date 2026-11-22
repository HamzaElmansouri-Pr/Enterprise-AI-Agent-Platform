# EAAP-010 — Secure multi-turn conversations and bounded memory

Status: engineering delivery pending independent review. Start baseline was accepted master `c99521e5e9500032f84dbac629475338304d966c`, containing merged EAAP-009 PR12 and corrective commit `ff25d4f`. Initial working tree was clean. Branch: `feat/eaap-010-secure-conversations`. No EAAP-011 work, paid provider call, development database change or merge is authorized by this task.

## Implementation and files

- `conversation/domain/ConversationRecords`: validated bounded titles, redacted conversation/message/turn/page/reservation records. Question validation reuses the existing domain `RunInput`.
- `conversation/application/Conversations`, `ConversationStore`, `ConversationRuntime`: current membership and creator-scoped use cases, durable reservation/orchestration, shared execution admission/quota, selected history and a narrow typed runtime port.
- `conversation/infrastructure/JdbcConversationStore`, `ConversationConfiguration`: transaction-bound creator context, prepared SQL, ordering/locking/conditional metadata transitions, run-backed message projection, bounded history/pages and safe DB failures.
- `conversation/api/ConversationController`, exact Spring Security matchers and shared execution error mapping: eight approved operation shapes, strict bounded JSON, JWT subject actor, no-store DTOs and read-only provider-outcome retrieval by request UUID.
- `V8__secure_conversations.sql`: two new tables, composite ownership/run references, request/ordinal uniqueness, forced RLS, invoker quota/state triggers and narrow runtime grants. V1–V7 remain unchanged.
- `execution/application/GroundedAnswer` and existing `AgentRuns`: share the accepted evidence cap and output/citation validation; preserve single-turn behavior. `HttpExecutionRuntime` supports the dedicated conversation path on the same bean/client, secret and deadlines.
- Python `execution/contracts.py`, `graph.py`, `api/executions.py`: strict bounded complete pairs, JSON-delimited untrusted history, aggregate prompt cap and a dedicated internal route through the same real LangGraph/provider/concurrency implementation. No independent memory, tool execution, database access, provider override or production fake switch.
- React `api/conversations.ts`, `features/conversations/Conversations.tsx`, Console, AgentSelect and scoped CSS: typed private list/chat, archived-agent history, create/rename/close, safe bubbles/citations, chronological pagination, explicit cost notice, stable pending request registry and manual outcome retrieval. Existing other-agent selectors remain DRAFT-only by default.
- New Java `ConversationIT`, `ConversationTestDatabase`, `ConversationDomainTest`, plus extended runtime HTTP test and existing fixture migration/truncation updates; Python `test_conversations.py`; React `conversations.test.tsx`.
- [ADR 0009](../architecture/decisions/0009-secure-agent-conversations.md), [conversation operations](../engineering/conversation-operations.md), this evidence and README/index links document actual behavior and limitations.

No framework, dependency, service, broker or database alternative was added. Existing Java/Python/npm manifests and lockfiles are unchanged.

## Schema and lifecycle

`conversations` has immutable tenant/agent/creator identity, bounded title, OPEN/CLOSED and creation/update timestamps. `conversation_turns` is the immutable request reservation, with ordinal1–100, stable USER/ASSISTANT IDs and one unique source run. Composite FKs enforce matching tenant/agent/user on both parents. Each turn projects two messages, with content, assistant status, citations, actual usage and failures stored once in the existing run. USER ordinal is2n−1, ASSISTANT2n. Failed turns still count; at most20 conversations/user/tenant include closed conversations.

All roles can access only their own conversations with current enabled membership. Manager roles never add supervisor access. Both new tables force tenant-and-creator RLS; runtime cannot rewrite turn history, change conversation identity/ownership, delete or bypass RLS. User/tenant GUCs assert trusted application identity; they are not cryptographic DB proof and do not protect against complete runtime SQL credential compromise. Source run linkage across creators is rejected by PostgreSQL.

DRAFT agents allow creation/messages; archived agents retain readable history. CLOSED is terminal and read-only. Close refuses an active turn; repeated close conflicts. A metadata timestamp records reservation or rename/close activity rather than an independent completion event. Membership removal preserves history/global identities but blocks future access.

## Execution, context and recovery

A request UUID is unique per conversation. Under a short parent-row transaction, new reservation checks OPEN/DRAFT,100 turns/one active run, admits one existing tenant daily attempt, creates the RUNNING run and inserts the turn atomically. Failure rolls the reservation/quota back. Shared process concurrency remains two; daily20 is shared with ordinary execution and generated proposals. Duplicate same-ID/same-question returns the original run/outcome without another admission or intentional dispatch; different question conflicts. Status retrieval never calls the provider.

No transaction spans inference. EAAP-005 retrieval uses the current question and selected agent's READY associations. History is the newest six SUCCEEDED complete pairs totaling≤8000Unicode characters, returned chronologically; incomplete/failed/pending/other-conversation turns are excluded. Evidence remains≤six passages/8000characters, question≤1000 and stored instructions≤10000. Python independently validates bounds and assembled content≤40000characters before its one provider node; accepted single-turn keeps its24000 bound. History/evidence are untrusted JSON data, not system instructions or authorization.

Existing source-ID validation maps only current authorized S1–S6 to document/chunk/filename/page snapshots. Historical citations survive association removal without granting source-byte access. No conversation evidence embedding, summarization, training or autonomous tool/approval flow exists.

Reserved execution outcomes use HTTP200 with RUNNING/SUCCEEDED/FAILED; FAILED has no invented answer. Failures retain safe metadata and actual usage when available. Ambiguous/network/database outcomes preserve request identity. Reads recover stale RUNNING after180seconds to FAILED/EXECUTION_INTERRUPTED, never rerunning inference; conditional terminal writes fence late results. A crash can occur before or after external processing, so exactly-once provider execution/billing is explicitly not claimed. Revocation during inference can block final persistence; later authorized recovery is required.

The client retains ambiguous IDs across feature navigation in console memory and offers GET-only resolution. A subsequent404 does not discard an ambiguous ID; a definite rejected send releases the draft. There is no automatic POST retry. Unresolved IDs may require operator inspection or session-end recovery; persisted messages expose request/run IDs after reauthentication. Logout/tenant switch clears private memory, not PostgreSQL history.

## Automated validation

Completed on2026-11-22, with no skipped tests:

| Check | Command | Result |
| --- | --- | --- |
| Full Java | `(cd services/control-plane && ./mvnw -B -ntp verify)` | 79 unit +179 integration =258 passed; zero failures/errors/skips |
| PostgreSQL | Included real Testcontainers in full verify | Fresh V1–V8, V7→V8, repeated0; ownership/RLS/ACL, run FK,100/20 caps, concurrency, replay, quota, stale/late fencing, retained citations and persistence failures passed |
| Python | `(cd services/agent-runtime && uv run --frozen ruff check . && uv run --frozen ruff format --check . && uv run --frozen pytest)` | Ruff passed;106 tests passed, including13 new real-LangGraph/internal-contract cases |
| Frontend | `(cd apps/web && npm run lint && npm run typecheck && npm test && npm run build)` | Passed;88 tests including14 conversation cases; production bundle built |
| Dependency review | `npm --prefix apps/web audit --json` and manifest/lockfile diff | npm zero advisories; no dependency/manifest/lock changes. Not a guarantee that every existing dependency/image is vulnerability-free |

Java evidence: `/tmp/eaap010-verified-java.log` plus Surefire/Failsafe XML; Python `/tmp/eaap010-python.log`; npm audit `/tmp/eaap010-npm-audit.json`. Temporary host tools/caches were the existing `/tmp/eaap-001-*` installations, not new repository dependencies. One concurrency assertion was synchronized with completion before terminal equality comparison; the test still independently checks pending replay, one call and one admission. PostgreSQL also exposed a locking-read visibility issue for CLOSED rows: the adapter now distinguishes readable closed metadata from UPDATE-policy-hidden locking rows and returns the correct409 without weakening RLS.

## Docker, browser and HTTP acceptance

Temporary harness `/tmp/eaap010-compose-verify.py` used distinct verification images and project `eaap-010-verification`, fresh project volume, control-plane18100/web14193, external temporary keys/generated synthetic credentials, the existing pinned local embedding model and existing four Compose services/limits. It bootstrapped a synthetic OWNER, then created a MEMBER through the real invitation API. Normal development containers/data were never migrated, reset or reseeded.

The shipped stack first ran with no provider: real TXT ingestion reached READY, a conversation returned durable FAILED/MODEL_UNAVAILABLE, and replay returned the identical run/result. A temporary external test-only module then supplied OpenAI SDK mocked HTTP transport; Spring, PostgreSQL, real local semantic retrieval, FastAPI, the actual LangGraph graph, output parsing and citation validation all remained active. No live paid inference or hosted answer quality was tested.

Completed27 HTTP/Compose checks and9 Chromium workflow groups:

- Four service config/build/startup, fresh/repeated V8 migration and actual local ingestion succeeded.
- Browser created a private conversation, sent a grounded first turn with real document/chunk citation mapping, then a follow-up with the first complete pair supplied as history.
- Two immediate send clicks dispatched one logical follow-up; explicit replay returned the first run without another SDK call.
- Logout/normal reauthentication restored persisted messages. Other user's list/get hid the creator's conversation.
- Literal malicious HTML rendered as text. Rename, keyboard-cancellable close confirmation and terminal close worked.
- Mobile390×844 had no horizontal page overflow; screenshot `/tmp/eaap010-mobile.png` was visually inspected.
- Actual control-plane process restart preserved CLOSED state and six chronological messages.
- Test-provider proof recorded exactly3 calls with history lengths `[0,1,2]` and verified the expected follow-up context and strict aggregate bounds. Replay, login and restart added no provider invocation.
- No localStorage/sessionStorage/cookies, cross-origin browser request, JavaScript error or secret-bearing URL/browser/container log was found. Container review also excluded synthetic private evidence/questions.
- Disposable project volume/network/containers and generated key/credential files were removed.

Evidence: `/tmp/eaap010-compose-summary.log`, `/tmp/eaap-010-verification/acceptance-results.json`, `/tmp/eaap-010-verification/browser.log`. These are temporary session artifacts; portable setup/API/test instructions are in operations. Final diff, documentation link, shell-example syntax and high-signal secret-pattern checks passed before commit. CI links and actual pushed commit are reported in the PR/final response rather than assumed from local passes.

## Performance, privacy and review limits

Pages are size1–100/offset≤10000, size+1 hasNext with deterministic ordering; message IDs/ordinals are stable. History selects at most six successful rows from the bounded100-turn conversation and no full-context cache/N+1 is introduced. Parent/advisory locks serialize only short DB work; existing pool/statement/lock/provider deadlines and four-service resource caps remain. The extra run uniqueness index and FK/trigger DDL require rollout locking/volume review. No latency or throughput benchmark is claimed.

Stored content and backups require a production retention/deletion/privacy policy. Provider egress includes bounded selected customer content; provider store=false is not a blanket compliance claim. No deletion, conversation sharing/transfer, summaries, long-term memory, streaming, new tools, model selection or production deployment is included. Prompt boundaries do not eliminate injection or certify truth. Complete runtime/privileged DB compromise, independent provider retention and hosted answer quality remain outside verified guarantees.

Review the run-backed paired-message representation, creator RLS assertion, aggregate-character policy, known in-flight authorization races, uncertain outcome UI, and no-retry recovery semantics. No approved requirement is intentionally dropped. Keep the PR unmerged and stop after EAAP-010 for independent architectural/security review.
