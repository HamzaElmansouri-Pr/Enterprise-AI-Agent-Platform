# EAAP-002 — Persistence and tenant isolation foundation

Status: implemented and verified locally; independent review and actual remote CI results remain separate delivery evidence. The task was assigned after acceptance of EAAP-000 and EAAP-001. No EAAP-003 work is included.

## Inspection and scope

The working tree was clean on `chore/eaap-001-project-scaffolding`. Fetching existing `origin` confirmed EAAP-001 merged into `master` at `30e8f8e`; its tree matched the accepted task branch. `feat/eaap-002-persistence-tenancy` was created from that merge. Existing instructions, engineering standards, application configuration, Compose, and CI were inspected. No unrelated work existed or was overwritten. The approved Spring Boot/Java, Python, React, PostgreSQL, container versions, and immutable GitHub Actions are preserved.

Spring Boot dependency management supplies Spring Data JPA (Spring Data BOM), Hibernate 6.6.53.Final, Flyway core/PostgreSQL support 11.7.2, PostgreSQL JDBC 42.7.11, Testcontainers 1.21.4, and Failsafe 3.5.6. No replacement ORM, migration engine, database, or test framework is introduced. No dependency versions are overridden.

## Implementation and schema

- `infrastructure/docker/postgres/bootstrap.sh` and `bootstrap.sql`: administrator-only role/schema provisioning on fresh volumes, plus an explicit existing-volume command. Dedicated passwords must differ. Passwords are not passed in CLI arguments or echoed; provisioning disables password-bearing statement logging only for that administrator session.
- `V1__tenant_identity_and_isolation.sql`: migration-owned `platform.tenants`, context function, self-read RLS policy, and explicit runtime grants. Columns are UUID primary key `id`, non-null non-blank `varchar(200)` name, and non-null `timestamptz` creation/update fields with ordering constraint. Only the primary-key index is added. No vector extension or future business tables are created.
- `PostgresTenantContext`: internal JDBC adapter requiring an explicit transaction and authorized UUID; prepared `set_config(..., true)` is transaction-local. No HTTP tenant selector, repository filtering substitute, JPA entities, domain dependency on Spring/JPA, business use case, or speculative interface is added.
- `application.yaml`: fixed runtime role, bounded zero-minimum-idle Hikari pool, driver/query/lock/idle-transaction timeouts, no Hibernate DDL or SQL initialization, no startup metadata lookup, and database-independent health.
- `application-migration.yaml` and entry-point handling: explicit one-shot migration execution using only the migration role, no HTTP/JPA, normal Flyway history validation, no schema auto-creation/baseline/clean, and process exit after completion.
- Compose: same four services/ports/limits/volume; read-only bootstrap mounts and separated role credentials. The Python/frontend configuration and implementation remain unchanged and receive no database credentials.
- Maven/CI: Failsafe runs PostgreSQL integration tests during `verify`; the existing Java job executes them with runner Docker. Image builds use `package` for unit/security tests without exposing a Docker socket inside the builder. Python/frontend CI remains independent.

The optional recommended `status` field is deferred: EAAP-002 supplies no lifecycle values/semantics. A lifecycle question was presented to the architect; no unapproved states are persisted. The task explicitly calls fields recommended and conditions state validation on a model containing lifecycle states. Lifecycle rules require a later approved migration; this omission does not weaken tenant isolation or invent behavior. No tenant lifecycle or administration API exists. `updated_at` maintenance belongs to future authorized persistence code; no speculative trigger is added.

## Roles, RLS, and limits

See [ADR 0001](../architecture/decisions/0001-postgresql-tenant-isolation.md) and [database operations](../engineering/database-operations.md). Bootstrap is privileged; `eaap_migration` owns only the platform schema/objects and has no administrative flags or memberships; `eaap_runtime` has no ownership/administrative flags/memberships and cannot create schema objects, truncate tables, alter policies, access migration history, or write tenant registry entries. Runtime gets SELECT on only its contextual tenant registry row. No global discovery exception is created.

RLS is enabled and forced on the production tenant table and representative test-only tenant-owned table. Policies use `platform.current_tenant_id()`: absent/empty context yields NULL; invalid UUID cannot authorize access. Test CRUD grants exist only on the fixture. The context function is SECURITY INVOKER with a fixed empty search path and no PUBLIC EXECUTE privilege.

A custom tenant setting is an assertion by trusted code, not authentication. A compromised runtime SQL channel could assert another identity; the future authenticated application layer must authorize identity before setting context on the use case's transaction connection. No authentication is claimed implemented. Commit/rollback reset context; pooling alone does not reset custom settings. Tests reuse one physical backend across transactions to prove this behavior.

The runtime pool is at most 4 connections with minimum idle 0. Pool acquisition is 2 seconds, validation 1 second, driver connect 3 seconds/read 5 seconds, server statement 3 seconds/lock 1 second/idle transaction 10 seconds. Migration mode uses at most 2 pooled connections. Compose runtime caps remain 2 GiB total plus engine/build overhead. No workload benchmark, caching, heavy worker, extra database index, or application polling is introduced.

## Validation evidence

Local commands use JDK 21 via `JAVA_HOME=/tmp/eaap-001-jdk`, isolated Maven user/cache paths under `/tmp`, and `DEBUG=false`, as recorded for EAAP-001. Docker-dependent operations run outside this environment's restricted sandbox. Testcontainers creates its own disposable database; it never connects to development volumes. Compose verification uses project `eaap-002-verification`, its own named volume, and three random passwords in a mode-600 `/tmp/eaap-002-compose.env` file. Password values are never recorded here.

`./mvnw -B -ntp verify` (with `MAVEN_USER_HOME=/tmp/eaap-001-maven` and `-Dmaven.repo.local=/tmp/eaap-001-m2`) passes 9 HTTP security/liveness tests and 13 database integration tests. Coverage includes fresh migration/constraints, role restrictions, runtime Flyway rejection, missing context, tenant A/B reads, rejected cross-tenant writes/reassignment, allowed own writes, forced RLS and row-security-off denial, commit/rollback cleanup, physical connection reuse, invalid context recovery, no-autocommit/null identity guard, rejected missing/shared bootstrap secrets, credential-log checks, and repeated Flyway validation/migration through the actual Spring migration profile.

Container validation used the prefix `DOCKER_CONFIG=/tmp/eaap-001-docker-config docker compose --env-file /tmp/eaap-002-compose.env -p eaap-002-verification` from the repository root:

- `config --quiet`, `up -d --wait postgres`, and `build control-plane`: passed; the image build ran all 9 unit/security tests. No Docker socket is mounted inside image builds.
- `exec -T postgres sh /docker-entrypoint-initdb.d/10-platform-roles.sh`: passed both before and after migration, preserving history and schema. This tests the explicit existing-volume provisioner without touching development data.
- `python3 /tmp/eaap-002-compose-lifecycle.py`: invokes `run --rm --no-deps -e MIGRATION_DB_PASSWORD control-plane --spring.profiles.active=migration` twice. First applies V1; second validates history and reports no pending migration. Both exit successfully without an HTTP server.
- `stop postgres`, then `up -d --no-build --wait --wait-timeout 120 control-plane agent-runtime web`: both APIs cold-started and returned exact expected health bodies, and an unrelated Java route returned 403. `start --wait --wait-timeout 60 postgres` restored all four services to healthy, with zero automatic restarts.
- Read-only PostgreSQL queries verified one successful Flyway version, zero activated vector extensions, zero tenant seed records, and zero `eaap_runtime` startup/health connections. Container inspection verified loopback port bindings, no PostgreSQL host port, and absence of migration/bootstrap credentials from ordinary app containers or any database credentials from Python/frontend.
- Container and migration logs were inspected for actual generated password values and startup error markers; none appeared. `sh -n infrastructure/docker/postgres/bootstrap.sh`, whitespace/diff checks, documentation links, immutable CI action pins, unchanged AGENTS.md, and unchanged Python/frontend sources were verified.
- Verification containers and only the newly created `eaap-002-verification_postgres-data` volume are cleaned after testing. Existing development volumes are never reset or removed by this task or Compose initialization.

The first actual one-shot migration exposed Flyway's need for two simultaneous connections; a one-connection pool timed out. The profile now has a bounded two-connection pool, and an integration regression starts that exact profile. All final migration executions pass. The old image-build `dependency:go-offline` prefetch stalled for several minutes before compilation; removing that redundant step left the normal `package` build and its unit tests, which completed successfully. The full host/CI `verify` still runs every integration test. Remote CI status is reported only after execution.

## Migration and recovery

The operator provisions roles, then invokes the existing control-plane container once with `--spring.profiles.active=migration` and explicitly forwarded migration secret. Normal runtime cannot migrate and does not receive that secret. The first version creates only new platform objects; repeated execution validates history and does not reapply it. PostgreSQL DDL is transactional. Failed versions require error/history review; preserve applied migrations and use reviewed forward corrections. No automatic clean, repair, down migration, credential reset, volume reset, or data deletion occurs. See the operations guide for existing-volume and failed-bootstrap recovery.

## Review notes and limitations

Review the lifecycle omission, privileged discovery/administration boundary, and custom-setting trust model. Production authentication/authorization integration, lifecycle policy, database TLS/secret-management/rotation procedures, business schemas/use cases, and readiness beyond process health are not implemented. Database admin and migration credentials remain trusted operational capabilities. No deviations from mandated dependencies or infrastructure were introduced.

Final branch, commit, push, PR, and actual CI evidence are supplied in the delivery report to avoid claiming a not-yet-created commit or review result in this document.
