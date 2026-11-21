# EAAP-005 — Secure knowledge ingestion and semantic retrieval

Engineering implementation of the approved coherent vertical slice, pending independent architectural review. EAAP-000 through EAAP-004 are accepted. Work started from a clean tree at latest `origin/master`, `611f129`, on `feat/eaap-005-knowledge-retrieval`.

## Delivered boundaries

See [ADR 0004](../architecture/decisions/0004-knowledge-ingestion-and-retrieval.md) for full contracts/decisions and [knowledge operations](../engineering/knowledge-operations.md) for exact setup/sample commands. Spring owns tenant-owned documents, private BYTEA sources, associations, extraction/chunking, durable state/leases, authorization and vector SQL. FastAPI computes only the fixed local model's CPU embeddings, with an injected internal secret, bounded batches and no database/customer JWT credentials. No frontend behavior or new service was introduced.

Significant changes:

- `knowledge/domain`: upload/text/vector validation, typed document lifecycle and metadata, deterministic Unicode-safe chunks.
- `knowledge/application`: authorized upload/read/list/retry/association/search, specific persistence/extraction/embedding/job ports, fenced processing orchestration.
- `knowledge/infrastructure`: prepared runtime JDBC, narrow dispatcher capability validation, PDFBox extraction, bounded authenticated HTTP embedding client, single in-process worker.
- `knowledge/api`: strict bounded multipart/search transport, metadata and grounded results, safe errors. Existing Spring Security adds only exact approved routes/CSRF exemptions; shared error handling maps real multipart oversize to 413.
- V4 and administrator extension SQL: four forced-RLS knowledge tables, composite tenant FKs, immutable source/chunks, constrained 384D vectors/model IDs, concurrent quota trigger, narrow fixed job claim/renew functions. V1–V3 and `PostgresTenantContext`, `AuthorizedTenant`, `TenantTransactions` remain unchanged.
- Python local adapter/setup/smoke/internal API: FastEmbed 0.9.1, locked ONNX Runtime 1.32.0, official multilingual-e5-small artifact revision `614241f622f53c4eeff9890bdc4f31cfecc418b3`, mean pooling/L2 normalization and explicit query/passage prefixes. No test fake is packaged as a runtime fallback.
- Opt-in Compose overlay: prepared model mount, internal secret, private embedding endpoint and measured Python memory adjustment. Baseline worker remains disabled until setup; `dev.sh up` explicitly provisions pgvector before migration. Existing dev data was not used in automated tests or migrated for this task.

## Validation commands

```sh
(cd services/control-plane && ./mvnw -B -ntp clean verify)
(cd services/agent-runtime && uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked pytest)
(cd apps/web && npm run lint && npm run typecheck && npm test && npm run build)
```

**Executed results:** Maven verify passed **153 Java tests** (59 unit/security and 94 integration), with zero failures/errors/skips. Python Ruff lint/format and **28 tests** passed. Frontend lint/typecheck, **1 test** and production build passed. A clean verify was also run before the final focused boundary additions.

Normal tests use disposable Testcontainers PostgreSQL, generated credentials/RSA keys and clearly test-only embedding adapters. They require no model download or paid calls. Original suites remain active; migration-count assertions now account for V4 and fixtures explicitly provision the administrator-owned extension. Coverage includes actual HTTP multipart limits, PDF/TXT parsing, Unicode chunk bounds, current/forged/expired identity, role permissions, quotas/retries under concurrency, real pgvector rankings with deterministic vectors, cross-tenant metadata/source/association/chunk/vector RLS without Java filters, invalid vectors/model IDs, pooled context cleanup, safe failures, rollback, leases, interruption/stale recovery and atomic READY publication.

Separate real adapter commands (explicit model setup requires network):

```sh
cd services/agent-runtime
uv run --locked python -m agent_runtime.embedding.setup "$HOME/.cache/eaap/models/e5-small"
HF_HUB_OFFLINE=1 uv run --locked python -m agent_runtime.embedding.smoke "$HOME/.cache/eaap/models/e5-small"
```

The documented standalone smoke module also passed with `HF_HUB_OFFLINE=1` (2.666 seconds, 929,736 KiB peak RSS). The real CPU smoke was executed using prepared temporary assets outside the repository: three synthetic English/French/Arabic passages plus one English query returned finite normalized 384D vectors. Observed initial load/inference took about 4.18 seconds with 935,068 KiB peak RSS; a separate maximum batch of eight 1,200-character passages passed at 930,644 KiB peak RSS. Assets occupied about 465 MiB. These are local observations, not semantic-quality/throughput benchmarks. The opt-in Python cap is 1,536 MiB, increased from 512 MiB based on these measurements. The isolated container used about 806.3 MiB after real inference, with its 1.5 GiB hard cap and no OOM kill verified.

Isolated Compose verification used a separate project, loopback ports, generated keys/secrets and disposable volume. Config/build/startup, privileged extension setup, fresh/repeated Flyway migration, owner bootstrap, signed login, multipart upload, durable PENDING→READY processing using the real offline local model, and actual pgvector-ranked passage retrieval passed. Two synthetic documents produced scores about 0.8493 and 0.7510 for the test admission query, with the admission source ranked first; this is an executable smoke example, not a general relevance claim. Archived search/invalid topK/unsupported retry were denied. Exactly four services remained, PostgreSQL/Python had no host ports, and Python had no DB/JWT credentials. Log review found no test secrets, tokens, hashes, private keys or private-text fixtures. Disposable containers/volume and temporary test credentials/keys were removed; the normal development stack was preserved.

Repository review checks internal Markdown links, staged whitespace/paths, recognized secret patterns, unchanged immutable migrations/context/capabilities, shell syntax and unchanged frontend sources. Dependency changes are the approved PDFBox 3.0.8 and FastEmbed stack; no new broker, database, AI framework or security product was selected. No dedicated vulnerability scanner or production penetration/performance test is claimed. Actual final suite counts and remote CI/commit/PR evidence are recorded in the delivery report after validation/push.

## Limitations and review notes

This is not production readiness. Review the narrow SECURITY DEFINER dispatcher policies, fencing and runtime state-column grants, the explicit administrator-owned pgvector setup/locking, and the trusted-network/shared-secret boundary. Runtime SQL compromise retains the asserted-context limitation from ADR 0001. One local process/embedding operation is the target; distributed global concurrency, parser process isolation, robust malicious-PDF sandboxing, TLS/service identity, source deletion/retention, complete association browsing beyond the bounded metadata window, cursor search, OCR, generated answers, UI, LLMs, tools and agent execution are outside scope. Failed documents consume the approved quota. Explicit worker disablement leaves work PENDING until enabled. Model busy/unavailable failures need explicit retry; no silent model substitution or hidden retry loop exists.

Required commit: `feat: implement tenant-isolated knowledge ingestion and semantic retrieval`. Push this branch and open a PR against master; leave it unmerged. Stop at EAAP-005 for independent review. No unapproved-scope deviation or blocking architecture decision remains; future features require separate approval.
