# ADR 0001 — PostgreSQL persistence and tenant isolation

Status: database isolation approach approved by task EAAP-002; implementation pending independent review. Tenant lifecycle values require a separate architect answer and are not approved by this ADR.

The initial `platform.tenants` table contains UUID `id`, a non-blank name of at most 200 characters, and non-null `timestamptz` creation/update timestamps with an ordering constraint. Only the primary-key index is added. EAAP-002 lists lifecycle status as recommended, not mandatory; no lifecycle states are specified, so `status` is deferred rather than inventing product rules. Add lifecycle states/constraints in a new approved migration when defined. There are no lifecycle APIs. Future authorized persistence code must maintain `updated_at`; no update behavior or speculative trigger is introduced here.

## Context and authority

EAAP-002 assigns relational persistence to the Spring Boot control plane, approves JPA/Flyway/PostgreSQL JDBC/Testcontainers, and requires separate bootstrap/migration/runtime privileges, transaction-local tenant settings, and database-enforced isolation. This ADR records that supplied decision and its implementation within [AGENTS.md](../../../AGENTS.md). Authentication, tenancy administration, business APIs, and production secret-management products remain outside scope.

## Decisions

Use the existing PostgreSQL 17 image with three trust levels:

| Role | Responsibility and privileges |
| --- | --- |
| Bootstrap administrator | Operator-only database/role provisioning. Local Compose retains its existing `POSTGRES_USER` bootstrap account; its privileges must never be used by the HTTP application. Production provisioning remains an operator responsibility. |
| `eaap_migration` | Login with ownership of the `platform` schema and its managed objects, sufficient for reviewed Flyway DDL. No superuser, BYPASSRLS, role creation, database creation, replication, or role memberships. No database-wide CREATE/TEMP privilege. Schema ownership remains a privileged trust boundary: the role can alter policies and must be isolated from ordinary runtime. |
| `eaap_runtime` | Login with CONNECT, schema USAGE, EXECUTE on the tenant-context function, and SELECT on its own tenant registry row. No ownership, elevated flags, memberships, schema creation, temporary tables, TRUNCATE, migration-history access, or tenant lifecycle writes. Future business-table grants must be explicit in reviewed migrations. |

Bootstrap revokes PUBLIC access to the database and public/platform schemas and provisions dedicated roles with distinct local passwords. It is separate from schema migrations and runs automatically only for a new PostgreSQL volume. Existing volumes require explicit operator invocation; they are never reset automatically. Ordinary startup receives only runtime credentials.

Run Flyway in an explicit one-shot `migration` profile of the existing control-plane image. It starts no HTTP server or JPA entity manager, uses migration credentials, validates history, applies pending versions, and exits. Normal runtime disables Flyway, SQL initialization, Hibernate DDL, startup metadata discovery, and the database health contributor. This keeps process health independent of database availability. No entities are mapped yet; `ddl-auto=none` prevents schema mutation. Future entity mappings must introduce validation deliberately without breaking the documented liveness boundary.

For each tenant-scoped transaction, trusted application code must supply an already authorized UUID to `PostgresTenantContext.apply` on that transaction's connection. The helper rejects autocommit/null identity, binds the UUID as a prepared parameter, and calls `set_config('app.tenant_id', value, true)`. The setting is transaction-local, not a persistent session setting or thread-local variable. Never derive it directly from unauthenticated request headers, URL parameters, or LLM output. Future authenticated application use cases own the transaction, identity authorization, and use of the same connection for setting context and performing persistence work; that request integration is not implemented here.

RLS policies compare the row's tenant UUID with `platform.current_tenant_id()`. This SECURITY INVOKER function treats absent/empty settings as NULL, parses non-empty settings as UUID, and has a fixed empty search path. Missing context exposes no rows and rejects tenant-owned inserts; malformed UUIDs cannot authorize access and cause a database error when evaluated. Enable and FORCE RLS so table ownership does not silently bypass row policies. Production registry reads compare `tenants.id` to the context; representative CRUD isolation is proved on a test-only tenant-owned table. No production fixture/business table is introduced.

The registry has no unrestricted discovery policy. Cross-tenant discovery, tenant creation/lifecycle changes, and platform administration require a future separately authorized mechanism. Do not grant those permissions to the runtime or weaken RLS to enable them.

## Consequences and security limits

RLS enforces row scope for an asserted context; it does not authenticate that assertion. The database runtime login can set a custom PostgreSQL setting. Compromised runtime credentials, SQL injection, or untrusted code with arbitrary SQL could impersonate a tenant by supplying another context. This design therefore requires application-owned authorization, parameterized SQL, no arbitrary-SQL tool, protected credentials, and the future trusted identity layer. Do not claim that RLS alone solves authentication or protects against compromise of trusted runtime code.

Commit and rollback reset transaction-local state. PostgreSQL may retain an empty custom-setting placeholder afterward; the function explicitly normalizes it. HikariCP does not clean arbitrary custom settings: correctness relies on always using an explicit transaction and `is_local=true`. Tests reuse the same physical backend across transactions and verify both commit and rollback. Session-wide `SET`, persistent tenant connection initialization, and switching context from public input are prohibited.

HikariCP is bounded at four runtime connections, zero idle minimum, two-second pool acquisition, and one-second validation. Driver connect/read timeouts and server statement/lock/idle-transaction limits bound failures. Migration mode uses at most two pooled connections. No caches, background workers, vector schema, or application database access in Python are introduced.

The migration login can change its own schema and policies and must remain restricted to reviewed operator-run migrations. Superusers inherently bypass RLS; administrator connections are used only for bootstrap/test fixture preparation, never to prove ordinary tenant access. Production secret injection, credential rotation, TLS policy, tenant lifecycle rules, and authenticated identity propagation require their own approved specifications.

## Verification and operations

See [database operations](../../engineering/database-operations.md) for initialization, one-shot migration, history/recovery, timeout behavior, and existing-volume handling. Testcontainers runs migrations on fresh disposable PostgreSQL, validates history on repeated execution, and exercises positive and negative behavior through real runtime-role connections. Existing default-deny HTTP tests run with an unreachable database. No development data is used by integration tests.
