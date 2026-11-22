# EAAP-009 — Tenant invitations and access management

Status: corrective delivery for [PR #12](https://github.com/HamzaElmansouri-Pr/Enterprise-AI-Agent-Platform/pull/12), pending independent architectural/security review. Do not merge or start EAAP-010 as part of this task. Baseline master was `7d4968758bb0b3098deb1f27e91b5e6e6942aa64`; reviewed checkpoint was `393277369dd7e4a16d3b598cd549efdee6cd0785`. Continue on `feat/eaap-009-tenant-access-management` without history rewriting.

## Correction and root causes

1. `PersistenceIsolationIT` still expected six migrations in its initial fresh-database setup even though Flyway correctly applied V7. Correct that assertion to seven. Existing assertions verify seven applied versions, history validation, repeated no-op and the offline migration profile. V1–V6 are unchanged.
2. The checkpoint accidentally changed the six-passage assertion to seven while updating migration-count assertions. Accepted master expected six; production `RunInput` rejects topK>6, retrieval applies LIMIT topK and `AgentRuns` enforces six passages plus8000characters. Restore the correct six expectation and strengthen the fixture with seven eligible documents, same-tenant unassociated and cross-tenant decoys, repeated deterministic retrieval order, source IDs S1–S6, exact text/document/chunk/filename/page correspondence and context bounds. The five-passage default remains tested. No production limit was increased.

The correction preserves application code and V7. Security review found no additional demonstrated privilege-escalation route reachable through an ordinary authenticated API caller. Extra tests cover V7 function ACLs/empty search paths/forced RLS, denied runtime table grants/private helper execution, direct runtime role abuse, disabled recipients/revoked issuers, duplicate issuance, redaction, cancellation/redemption and revocation/redemption races. The historical-retention fixture now includes an agent run as well as proposal and decision records. Browser automation required a locator correction for an accessible input label that includes hint text; this was not an application defect.

## Implementation and significant files

- `services/control-plane/.../access/domain`: canonical32-byte secret generation and SHA-256, password/role validation, redacted records and safe failure codes.
- `.../access/application`: existing membership authorization, bounded tenant use cases, specific persistence port and hashing outside transactions.
- `.../access/infrastructure`: fixed bound SQL capability calls, short transactions and sanitized database failures; direct binding avoids Spring parameter trace output.
- `.../access/api` and existing `SecurityConfiguration`: eight exact operations, strict4096-byte schemas, verified JWT actor, anonymous invite-only new acceptance, authenticated existing acceptance, independent bounded acceptance admission and no-store errors/results.
- `db/migration/V7__tenant_access_management.sql`: constrained invitations/access audit, forced RLS, eight narrow migration-owned capabilities, four private helpers and concurrency serialization; no new runtime identity-table DML.
- `PersistenceIsolationIT`, `ControlledAgentExecutionIT`, `TenantAccessIT`, `AccessTestDatabase`, `InvitationSecretTest`: migration, context, lifecycle, identity compatibility, security, concurrency, source mapping and redaction evidence. Existing fixture helpers include new FK dependencies in disposable truncation and correct migration counts.
- `apps/web/src/features/team/Team.tsx`, `auth/AcceptInvitation.tsx`, App/Login/TenantSelection/Console and existing API contracts/client: Team & Access, live role refresh, confirmations, transient one-time token, new/existing acceptance and scoped same-origin requests. `tests/team.test.tsx` adds13 tests to the existing61.
- [ADR 0008](../architecture/decisions/0008-tenant-invitations-and-access-management.md), [tenant access operations](../engineering/tenant-access-operations.md), README and related documentation indexes: permissions, setup, lifecycle, exact request walkthrough and limitations. Authentication operations now distinguishes invitation acceptance from unrestricted signup.

No new dependency, framework, service or database is introduced. Logical boundaries remain Spring authorization/persistence, React presentation and existing Python bounded execution. No paid provider call was made.

## Database security and performance evidence

Invitations use32random bytes, canonical43-character base64url and stored SHA-256 only; raw token returned once. Trusted database expiry is48hours; pending expiry is derived rather than scheduled. Five live invitations/tenant and one live normalized email/tenant are enforced under the same per-tenant transaction advisory lock used for access mutations. Redemption and its identity/membership/audit changes are atomic, with a second expiry check before consumption. Failed membership insertion rolls everything back. Existing membership returns409, password overwrite is impossible, consumed/cancelled/expired/wrong-recipient acceptance returns generic410. Predating-revocation invitations cannot restore removed membership.

Runtime has no direct global identity DML and no invitation/audit table grants. New SECURITY DEFINER functions are migration-owned, static SQL, empty search_path, no PUBLIC execution and narrow runtime grants. Both new tables force RLS. No runtime ownership, BYPASSRLS, DDL or role membership exists. The migration role remains privileged and can change schema/policies. Audit has no runtime mutation/read API and survives membership deletion; it is not tamper-proof against privileged operators.

Actor UUID parameters and transaction-local tenant settings are trusted application assertions, not cryptographic database authentication. The API binds actor to verified JWT subject and rejects authority fields; compromised application code/runtime credentials could impersonate a known actor. Existing V2 login's narrow password-hash capability is unchanged. No claim of protection against complete runtime compromise is made.

Directory/invitation pages are bounded at100 plus one hasNext row and offset10000. Queries join once rather than N+1. Digest, tenant/date, pending/email and revocation indexes match the access paths. Acceptance has2concurrent slots,5attempts/peer/minute,20/process/minute,1024peer entries, no queue; login retains a separate guard. JDBC query timeout3seconds, acceptance transaction timeout5seconds and existing lock/pool limits bound contention. All four existing service resource caps remain unchanged. No external service or provider participates in access management and no latency benchmark is claimed.

## Verification commands and results

Commands ran on2026-11-22 from the repository root (subdirectory shells as shown):

| Check | Command / evidence | Result |
| --- | --- | --- |
| Full Java verification | `(cd services/control-plane && ./mvnw -B -ntp verify)` | 75 unit +161 integration =236 tests; zero failures/errors/skips; BUILD SUCCESS |
| Fresh/repeated migrations and upgrade | Real PostgreSQL Testcontainers in the full verify | Fresh V1–V7, repeated0, V6→V7=1; checksums validated |
| V7 privilege/RLS | `PersistenceIsolationIT` and26 `TenantAccessIT` tests | Passed, including actor/context abuse and concurrent lifecycle tests |
| Python lint/format/tests | `(cd services/agent-runtime && uv run --frozen ruff check . && uv run --frozen ruff format --check . && uv run --frozen pytest)` | Lint passed,22files formatted,93tests passed |
| Frontend | `(cd apps/web && npm run lint && npm run typecheck && npm test && npm run build)` | Passed;74tests; production bundle built |

Host tool locations were `/tmp/eaap-001-jdk`, `/tmp/eaap-001-node`, `/tmp/eaap-001-tools/bin/uv`, with existing Maven/uv caches. Full Java output is `/tmp/eaap009-correction-verified-java.log`; authoritative totals were also read from Surefire/Failsafe XML. These local temporary paths are session evidence, not repository dependencies. GitHub Actions runs the same existing Java/Python/frontend jobs; the final PR/report records the corrective commit's remote result rather than treating a local pass as CI evidence.

## Isolated acceptance

A temporary Python standard-library harness builds distinct `eaap009-control-plane-verification:local` and `eaap009-web-verification:local` images and runs existing Compose files under project `eaap-009-verification`, with control-plane18090 and web14183. It retains the existing knowledge/execution overlays, local prepared embedding model and empty hosted-provider credentials. A fresh project volume receives V1–V7, repeated migration and two synthetic OWNERs via the existing offline bootstrap profile. All subsequent colleagues use the invitation APIs. No normal development service or database is migrated, reset or reseeded.

The HTTP workflow covers owner login; MEMBER invitation; one-time output; new acceptance without implicit JWT; normal login; correct tenant membership; MEMBER approval403; OWNER promotion; same JWT ADMIN context and approval200; prohibited role changes403; existing-account acceptance into tenantB; replay410; revocation; same JWT tenantA403 and tenantB200; preserved proposal/decision/access audit. Separate negative requests cover wrong email, invalid/cancelled/expired tokens. Real acceptance throttling is retained rather than disabled; the harness waits for its minute window when needed.

Chromium drives the actual Vite proxy and Spring backend: member listing/issuance, navigation clearing token, new-account form and normal login, MEMBER controls absent, OWNER confirmed promotion, same-session refreshed ADMIN and MEMBER-only invitation options, existing-account second-tenant form, confirmed revocation, immediate HTTP403 while another tenant remains accessible, token cleanup after tenant switch/logout, empty browser storage/cookies and no secret-bearing URLs/logs or JavaScript errors. No mocked browser endpoint or paid model call is used. The completed run passed39 HTTP checks and all7 Chromium workflow groups. Invalid, cancelled and expired tokens each returned410. Container logs contained none of the generated passwords, internal secrets, JWTs, invitation tokens or Argon2 hashes. Fresh/repeated offline migration, both owner bootstraps, all four service health checks and disposable cleanup passed. Local evidence: `/tmp/eaap009-compose-summary.log`, `/tmp/eaap-009-verification/acceptance-results.json` and `/tmp/eaap-009-verification/browser.log`; the temporary harness was `/tmp/eaap009-compose-verify.py`. Secret environment/signing/credential files were removed after teardown.

## Review and remaining limitations

Reviewers should examine the exact eight capability functions, trusted actor/GUC boundary, OWNER/self matrix, terminal-state handling and conservative pending-invitation invalidation rules in ADR0008. Invitation/audit retention, production TLS/deployment, distributed abuse protection, account recovery and secure external token delivery remain separate decisions. Shared proxy peer limits can throttle multiple local browser users; process restarts reset the limiter. Browser/clipboard compromise and privileged database compromise are outside the guarantees. No production readiness, mailbox verification, unrestricted registration or EAAP-010 authorization is claimed.

Final diff whitespace, high-signal credential/private-key patterns, documentation links and shell-example syntax checks passed. No secret environment/key file was added; V1–V6 are byte-for-byte unchanged from accepted master.

PR #12 must remain unmerged pending independent review. The corrective commit is reported by `git log -1` and the final delivery response; this document does not claim architect acceptance.
