# EAAP-006 — Controlled agent execution

Implements the approved bounded synchronous execution task. Started from clean latest master `8fcee72` after verifying merged EAAP-005 PR #7 and preserving master PR #8. Branch: `feat/eaap-006-controlled-agent-execution`. Pending independent review; do not merge or begin EAAP-007.

## Delivered responsibilities and files

- `services/control-plane/.../execution/domain`: bounded message/topK, private result/lifecycle shape and safe failures.
- `execution/application`: trusted orchestration, existing membership and DRAFT checks, existing knowledge search, process admission and execution-local source mapping; ports for runtime and run storage.
- `execution/infrastructure`: short prepared JDBC work under existing tenant transactions, atomic daily quota, conditional terminal writes, stale recovery, bounded internal HTTP and dependency wiring.
- `execution/api` plus exact Spring Security route additions: authenticated POST runs and creator-only GET status/result, strict JSON/no query authority, safe errors/no-store.
- V5 adds forced-RLS `agent_runs`/`agent_run_daily_quota`, composite tenant-agent ownership, immutable creator/question, lifecycle/resource constraints, narrow runtime updates and no deletes. V1–V4 and tenant capability/helper are unchanged.
- Python `execution/{contracts,state,graph,provider,service}` plus dedicated internal API: real typed four-node LangGraph, one opt-in OpenAI SDK invocation, strict output/citation validation, deadlines, actual usage and disabled private tracing/diagnostics.
- `compose.execution.yaml`, `.env.example`: opt-in configuration, separate service secret, internal Python port and provider key confined to Python. Four services only.
- [ADR 0005](../architecture/decisions/0005-controlled-agent-execution.md), [operations](../engineering/agent-execution-operations.md), architecture/task indexes and README links.
- Deterministic tests in Python and Java, test-only database/runtime fixtures; existing regression migration expectations advance to V5 and knowledge fixture cleanup includes new referencing tables. CI names document execution checks; no paid CI calls.

## Behavior and controls

POST `/api/v1/tenants/{tenantId}/agents/{agentId}/runs` accepts only message<=1,000 valid Unicode characters and topK default5/max6. All current enabled tenant roles can execute DRAFT agents. GET `.../runs/{runId}` requires current enabled membership, same-tenant agent and the immutable creator, including for OWNER/ADMIN. Archived starts409; creator status reads remain available.

Spring reuses EAAP-005 READY/associated/RLS retrieval and passes only bounded text/source IDs. Maximum six passages, 8,000 context characters and 24,000 assembled prompt characters; no invented similarity-confidence threshold. No content or model abstention gives422 INSUFFICIENT_KNOWLEDGE. No-context cases do not invoke the provider. Citation IDs must map to Spring's exact supplied sources; metadata is never accepted from the model, and duplicate references are deduplicated.

RUNNING becomes SUCCEEDED/FAILED in a separate short transaction after the external call. Success is returned only after persistence; database failures cannot become false success. Conditional updates prevent terminal overwrite, and 180-second stale recovery occurs on the next authorized owner start/read for that agent without model replay. No long database transaction, retry queue or background execution dispatcher is introduced.

Atomic PostgreSQL admission limits each tenant to20 attempts per UTC day, including failures/repeated client requests. Two nonblocking Spring slots (covering retrieval and runtime), two Python execution slots, existing bounded embeddings, one model call, <=800 output tokens and explicit connect/provider/graph/complete-response deadlines bound resource use. Limits are not coordinated globally across replicas. Hikari remains four connections. Sources/questions/answers/JWTs/provider keys are not telemetry. Actual token usage is nullable; known usage is retained even when an answer/citation is rejected.

## Verification commands and evidence

Executed locally with JDK21 and Docker/Testcontainers:

```sh
(cd services/control-plane && ./mvnw -B -ntp verify)
(cd services/agent-runtime && uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked pytest -q)
(cd apps/web && npm run lint && npm run typecheck && npm test && npm run build)
git diff --check
```

Host tool paths and Maven/uv caches were under `/tmp`; these do not alter the build contract. Java verification covers all EAAP-001–005 regressions plus real PostgreSQL run RLS/privilege/FK/lifecycle/ownership tests, role/current-enabled/membership/forged/expired authentication, strict inputs, actual authorized retrieval/context, missing knowledge, unknown/duplicate citations, nullable usage, provider failure, stale recovery, terminal fencing, concurrent20/30 quota admission, two occupied process slots/third rejection, and DB failure after a provider response. Embedding/provider fakes assert no transaction is active during external work. HTTP adapter tests verify no JWT/database/customer identity forwarding, dedicated headers, actual usage shape, sanitized errors, body cap and complete-body timeout.

Python tests exercise the actual graph order and exactly one invocation, typed strict/unicode/body/secret bounds, injection fixture separation, no production fake mode, inherited tracing suppression and installed OpenAI SDK request/parse/error/timeout/usage behavior through mock HTTP. They make no paid calls and do not require an API key/model download. Frontend source is unchanged; its existing checks remain active.

An isolated Compose verification script under `/tmp` provisions only a disposable database/project, migrates/repeats/bootstrap/starts four services, uploads synthetic admissions/physics files and performs real CPU embedding/pgvector retrieval. It verifies missing hosted credentials yield a persisted FAILED run, then mounts a **test-only** SDK mock outside the repository into Python to exercise Java→real StateGraph→installed SDK→durable result/citation GET and unauthorized tenant403. This is real retrieval plus mocked hosted completion, not real hosted answer-quality evidence. Test containers/volume/ephemeral keys and credentials are removed; the development stack/database is not migrated or restarted.

Final local results: **177 Java tests** (66 unit/security +111 integration), **63 Python tests**, and **1 frontend test**, all passing. Python lint/format and frontend lint/types/build pass. The final isolated Compose check observed Python **860.5 MiB /1.5 GiB** after real embedding plus graph/SDK-mock execution, without OOM; the synthetic mocked-completion run duration was4,652 ms including retrieval, not a hosted-provider latency measurement. Both images built and disposable cleanup passed. Commit SHA, PR URL and actual GitHub CI status are reported in the delivery response/associated PR. No dedicated vulnerability scanner or production throughput/latency benchmark is claimed.

## Provider and remaining limitations

The only opt-in model is `gpt-5.4-mini-2026-03-17`, reasoning effort none, via OpenAI SDK2.37.0 Responses strict structured output, store=false, retries0 and fixed api.openai.com URL. Official model/structured-output compatibility and installed SDK mocked contracts were verified; **no real authorized hosted call was made**. Account access and real grounded-answer quality remain unverified. Startup/health/migrations/agent management/retrieval require no provider key.

Valid citations do not establish factual support. Prompt boundaries cannot eliminate injection/hallucinations. Shared-secret Docker networking is not production workload identity/TLS; limits are per process, and lazy stale recovery waits for authorized access. Customer content leaves the local boundary when hosted execution is enabled. Provider data controls and retention/deletion/encryption/audit policies for stored questions/answers/citations/quota records require production review. A timed-out request may already be billed; there is no exactly-once or automatic retry guarantee. No tools, business actions, history, streaming, chat UI, multi-agent execution, new infrastructure service or unapproved framework is included.
