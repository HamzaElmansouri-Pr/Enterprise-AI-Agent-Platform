# EAAP-008 — Secure tenant operations console

Started from clean latest master `fca3fbd` (accepted EAAP-007 PR10 merged), branch `feat/eaap-008-tenant-operations-console`. Implements the approved EAAP-008 scope; pending independent architectural/security review. Do not merge or start EAAP-009.

## Implementation and significant files

- `apps/web/src/App.tsx`, `auth/`: real login, verified tenant selection, private memory-only token/session scopes, local/server401 expiry, logout and complete tenant-state replacement. No refresh/cookies/browser storage/tenant discovery.
- `api/contracts.ts`: runtime decoders derived from actual Java DTOs. `client.ts`: bounded cancellable JSON/multipart requests, no-store/omit cookies/no redirects, static sanitized status errors with safe request/run UUIDs. `tenant.ts`: fixed existing endpoint methods, no browser-to-Python or dynamic authority.
- `app/Console.tsx`, `components/`, `index.css`: responsive desktop sidebar/compact navigation, common fields, native focus-restoring dialogs, loading/error/empty states, textual live status, tables/pagination and scoped async hooks. Ordinary React text renders all customer/model content.
- `features/dashboard`: current UUID/role and honestly labeled bounded agent/document/proposal summaries. `features/agents`: status filter/list/page/get/create/edit/archive, manager-only writes, terminal archived read-only detail.
- `features/knowledge`: visible file limits, real multipart upload/association, status/count/failure, explicit retry, add/remove associations, sequential bounded pending polling and actual semantic results/citation metadata. Search scores are similarity, not confidence.
- `features/executions`: single-turn question/result, first-call cost/config notice, explicit own-run retrieval, sanitized failures, nullable model/usage/duration/citations. No automatic replay or fabricated answer.
- `features/approvals`: manual fixed-tool and optional explicit AI proposals, exact payload/digest review, creator self-approval disabled, deliberate different-user approval, bounded rejection reason, separate real-effect execution confirmation, result/task link and duplicate-click guard. MEMBER sees own proposals/AI generation and no manager controls.
- `features/admissions`: read-only bounded real OPEN task list with creator/source-proposal metadata.
- `vite.config.ts`, web Dockerfile, compose web build and `.env.example`: remove stale public API URL arguments; reuse Vite dev/preview with only fixed same-origin `/api/v1/` Spring proxy. Preview includes its config, non-root existing service and defensive headers, no extra service/CORS/backend change.
- Pinned test-only `@testing-library/react`16.3.3 and `jsdom`31.0.1 plus lockfile; no runtime dependency. `App.test.tsx`, `api/client.test.ts`, `tests/features.test.tsx` replace the obsolete static-page assertion with actual lifecycle/interaction tests. Test setup/fixtures are never imported by production.
- [ADR 0007](../architecture/decisions/0007-tenant-operations-console.md), [console operations](../engineering/web-console-operations.md), root README and architecture/task indexes.

All Java/Python application files, accepted migrationsV1–V6, API/security/membership/RLS behavior, dependency manifests and CI configuration remain unchanged. The console relies on actual server authorization and effect/idempotency semantics, never UI state as authority. No new domain, auth service, provider, model, tool, production mock or infrastructure service is added.

## Validation commands and outcomes

Executed using Node22/JDK21/uv and caches under `/tmp`:

```sh
(cd apps/web && npm run lint && npm run typecheck && npm test && npm run build)
(cd apps/web && npm audit --json)
(cd services/control-plane && ./mvnw -B -ntp verify)
(cd services/agent-runtime && uv run --locked ruff check . && uv run --locked ruff format --check . && uv run --locked pytest -q)
git diff --check
```

- Frontend: **61 tests**, all passing; ESLint, strict TypeScript and production build pass. Fixtures cover authentication/invalid/401/local expiry, memory-only startup/logout, authorized UUID selection, tenant-switch cancellation, pagination/roles/agent validation/conflict/archive, file rejection/multipart, READY/FAILED polling and60-refresh ceiling/unmount, retry/search/untrusted HTML as text, paid confirmation/provider503/504/quota/no replay/unknown usage, fixed manual schema, exact reviewed digest/no automatic action, separate approval/execution, self-approval/MEMBER controls, terminal rejection, stale409, duplicate clicks and durable task display.
- Java: **205 tests** (71 unit/security +134 PostgreSQL integration), full Maven verify BUILD SUCCESS. Python: **93 tests**, Ruff lint/format pass. The first restricted Python run stalled on loopback sockets and was interrupted; rerunning with local networking completed93/93. No tests disabled or paid calls made.
- `npm audit`: **zero reported vulnerabilities** at verification time. New dependencies are test-only, exact versions/lockfile reviewed. No dedicated image scanner or production benchmark is claimed.
- Host Vite was started temporarily on loopback15173 and proxied a real unauthenticated tenant context request to existing Spring8080:401, validated request UUID and no-store preserved. No existing development account/data was read or modified by that check; temporary Vite was stopped.
- Isolated four-service Compose configuration, web image build, PostgreSQL extension/role provisioning, V1–V6 fresh/repeat migrations, owner bootstrap and real startup passed. A synthetic second ADMIN was explicitly provisioned with the existing offline identity operator and accepted Argon2 encoder.
- Real Chromium through web14173 only: owner login/verified tenant, real agent creation, actual TXT multipart upload to READY, local embedding/pgvector search and actual passage/score, cost-confirmed unconfigured-provider failure plus durable run retrieval, manual proposal, disabled self-approval, different authorized manager approval without task, separate execution, one OPEN task and explicit HTTP replay returning same taskId. No browser HTTP mocks or paid model calls.
- Browser checked empty localStorage/sessionStorage/cookies, tenant switching/removal of old UI, reload to login, logout, mobile390px with no page overflow, same-origin-only requests and no page errors. Desktop/mobile/proposal screenshots show only synthetic data, stored as local artifacts outside Git.
- Secret/password/hash/private-key log checks passed. Disposable stack, volumes, keys, hashes and test credentials were removed. Normal development containers/data/accounts were not migrated, restarted or provisioned.

The final clean browser rerun passed, including keyboard Escape cancellation and focus restoration before approval. Evidence from the disposable stack: proposal `926815ad-393a-4a96-a81f-a45011238d9d`, task `495f93af-d411-4f7c-8378-3002cd77e4e5`; replay returned the same task UUID and the table contained exactly one OPEN task. No claim of real hosted model quality is made. GitHub CI status is reported in the delivery response/PR.

## Operations, performance, limitations and review

[Console operations](../engineering/web-console-operations.md) contains exact host/Compose configuration/startup, login/UUID, agent, knowledge, optional Q&A, two-person review/execute and explicit replay commands. Existing offline operator provisioning remains mandatory for a second reviewer; a single-user tenant cannot self-approve. Requests/polling/response bytes/pages are bounded, no background analytics/provider call occurs, and no backend N+1 or schema change is introduced. The production build emitted267.33kB JavaScript (80.75kB gzip) and8.98kB CSS (2.84kB gzip). No production throughput claims.

UI roles can become stale; Spring's current membership remains authoritative. In-memory tokens do not protect against a compromised script/browser. Reload loses the session/navigation; no deep-link router/history, refresh, tenant/run discovery or multi-tab synchronization is added. Browser maxlength can be stricter than server Unicode code-point limits. Association listing is capped by the existing100-ID contract. Hosted quality/account access, production TLS/hosting/identity, retention, distributed budgets and external-effect recovery remain separate review work. Vite preview remains development-only. There are no approved-scope deviations.

Branch/commit/push/PR and actual CI conclusion are recorded in the delivery response and associated PR, not preclaimed in this file. Stop at EAAP-008 and await independent review.
