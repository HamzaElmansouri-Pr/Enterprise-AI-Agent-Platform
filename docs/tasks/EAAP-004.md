# EAAP-004 — Tenant-owned agent configuration

Engineering delivery for the approved task; pending independent architectural acceptance. EAAP-000 through EAAP-003 are accepted. Work began from clean `origin/master` at `73e4f5e` on `feat/eaap-004-agent-configuration`.

## Delivered scope

[ADR 0003](../architecture/decisions/0003-agent-configuration-domain.md) records the model, API contract, validation limits, roles, RLS/privileges, shared authorized transaction boundary, concurrency behavior and limitations. New code is confined to the Spring Boot agent module, a small identity/tenancy transaction refactor, V3, focused tests, runtime logging protection, and documentation/CI labels. No dependencies changed. V1, V2, and `PostgresTenantContext` remain unchanged. Python/frontend functionality and the four-service topology remain unchanged.

The agent module has API/application/domain/infrastructure layers. It provides tenant-owned create, bounded list, retrieve, draft update and terminal idempotent archive. OWNER/ADMIN manage, MEMBER reads. SQL enforces tenant scope, immutable identity columns, and terminal archival in addition to current database membership checks. Sensitive instructions are literal configuration data; no execution is implemented.

## Executed validation

Commands run with JDK 21 and Docker available:

```sh
cd services/control-plane
./mvnw -B -ntp verify
```

Result: **113 Java tests passed**, zero failures/errors/skips: 35 unit/security tests and 78 PostgreSQL-backed integration cases. Includes 22 agent domain cases and 36 agent integration cases, plus all existing identity, persistence, and health regressions. A clean verify was also run before adding the new suites, avoiding stale classes from other branches.

Tests cover fresh V1–V3 and V2→V3 migration/validation, strict payloads/Unicode/limits, all roles, forged claims/headers, removed membership/disabled identity, tenant-local 404s, exact routes/CSRF, size+1 deterministic pagination/no COUNT, RLS without a Java tenant filter, runtime privilege denials, same physical connection context, rollback/pool/HTTP context cleanup, and sanitized errors/logs. Three latch-controlled races verify update versus archive in both orders and concurrent repeat archive. Test-only keys/passwords/databases are generated independently of development credentials.

Follow-up lifecycle verification: `./mvnw -B -ntp -Dit.test=AgentConfigurationIT verify` passed all 35 unit/security tests and 36 agent integration cases. The OWNER/ADMIN lifecycle cases additionally assert editable field values and use fresh GET requests after update, archive, and rejected archived update to prove persisted representations remain correct. This verification used disposable PostgreSQL only and did not create or archive development-tenant data.

```sh
cd services/agent-runtime
uv run --locked ruff check .
uv run --locked ruff format --check .
uv run --locked pytest
```

Result: lint/format passed, **10 Python tests passed**.

```sh
cd apps/web
npm run lint
npm run typecheck
npm test
npm run build
```

Result: all passed, **1 frontend test passed** and production bundle built.

An isolated Compose project (`eaap-004-verification`, alternate loopback ports, generated secrets/keys and disposable volume) passed config validation, image build, fresh migration, bootstrap/repeat, all four healthy services, missing-signing-key login 503, signed login/tenant authorization, agent create/list/read/update/archive/repeat, archived update 409, absent agent 404 and invalid page size 400. PostgreSQL remained private; migration/operator credentials were absent from HTTP/runtime/frontend environments. Container logs contained no test secrets, tokens, instruction fixture, hashes or private keys. Test containers/volume were removed; the existing development database was untouched.

Repository checks validate internal Markdown references, whitespace, immutable migrations/helper, no Python/frontend source changes, and recognized secret patterns. These are targeted reviews, not a dedicated vulnerability scan or a performance benchmark. GitHub Actions uses the same Java verify/Python/frontend checks; actual remote CI/commit/PR evidence is recorded in the delivery report after push.

## Review and operations

Review [database provisioning/migration procedures](../engineering/database-operations.md) before rollout. Existing database volumes require explicit reviewed role provisioning to apply runtime error logging defaults protecting sensitive configuration, then the existing one-shot V3 migration. Ordinary service startup never changes schema or resets data. No restore/delete, ETag/version history, cursor pagination, UI, model configuration or agent execution exists. Offset pagination is capped at 10,000 and draft-versus-draft edits use last successful writer semantics. No approved-scope deviation or new architecture dependency is proposed.

Required commit: `feat: implement tenant-owned agent configuration management`. Push the designated feature branch and open an unmerged PR against master. Stop at EAAP-004 for independent review.
