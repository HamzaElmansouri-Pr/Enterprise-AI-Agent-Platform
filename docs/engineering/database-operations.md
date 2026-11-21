# Database operations and trust boundaries

Follow [ADR 0001](../architecture/decisions/0001-postgresql-tenant-isolation.md) and [AGENTS.md](../../AGENTS.md). These commands use the existing four-service development environment; no migration service or production deployment is added.

## Local provisioning

Copy root `.env.example` to ignored `.env`. Set three different unique local passwords: `POSTGRES_PASSWORD` for bootstrap, `MIGRATION_DB_PASSWORD` for schema migration, and `DB_PASSWORD` for runtime. These are development credentials only, not production defaults. Do not commit `.env`, echo credentials, pass literal secrets in command arguments, or enable shell tracing around provisioning.

The PostgreSQL entrypoint runs the mounted bootstrap script on an empty data volume. It creates the migration/runtime roles, revokes public database/schema access, and creates the migration-owned `platform` schema. Passwords are read from the environment by psql; password-bearing statements are not echoed and statement/error-statement logging is disabled only in the administrator's provisioning session. Errors still fail the script and transaction. Application logs and normal database sessions do not inherit those logging settings.

```sh
docker compose config --quiet
docker compose up -d --wait postgres
docker compose build control-plane
```

For a volume initialized by EAAP-001, PostgreSQL does not rerun initialization scripts. After reviewing the existing database and backing up data, explicitly provision the new dedicated roles using:

```sh
docker compose exec -T postgres sh /docker-entrypoint-initdb.d/10-platform-roles.sh
```

This operator command updates the dedicated roles/passwords, removes their memberships, revokes PUBLIC database/schema privileges, and checks the platform schema owner. It does not remove tables, reset the volume, or migrate schemas. An unexpected existing platform owner fails provisioning for manual review. Review effects on other applications before using a shared database; the local project database is intended to be dedicated. Never delete a volume automatically to fix initialization or credentials.

## Migration process

Export `MIGRATION_DB_PASSWORD` into the invoking shell from your approved local secret source. Compose normally passes it only to the bootstrap container; the one-shot command explicitly forwards the existing shell variable to its temporary control-plane container. Do not type the password as a literal CLI argument. Host application commands do not automatically load `.env`.

```sh
docker compose run --rm --no-deps -e MIGRATION_DB_PASSWORD control-plane --spring.profiles.active=migration
docker compose up --build --wait
```

The temporary container overrides the default runtime datasource login with `eaap_migration`, starts no web server, excludes JPA initialization, validates/applies Flyway migrations, closes its context, and exits. The regular application starts with only `eaap_runtime` credentials and does not run migrations. Never enable `migration` on the persistent HTTP service. A database outage fails the migration command; runtime process health can still be UP, which does not assert database readiness.

The migration pool permits two connections because Flyway retains its metadata/lock connection while obtaining a migration connection. A one-connection pool deadlocks acquisition and is not supported by this profile. Both are closed when the one-shot process exits; the runtime pool remains separately capped at four with zero idle minimum.

For host migrations, export `DB_URL` and `MIGRATION_DB_PASSWORD`, then run `./mvnw spring-boot:run -Dspring-boot.run.profiles=migration` from `services/control-plane`. The URL must reference a securely reachable database; Compose intentionally publishes no PostgreSQL host port. The ordinary host service uses `DB_PASSWORD` and cannot migrate.

Flyway uses the existing `platform` schema and its migration-owned history table. Create-schemas, automatic baseline, destructive clean, and retry loops are disabled. Versions are applied in order; successful versions are validated and skipped on repeated runs. No extension is enabled, including pgvector, because this task has no vector schema.

## Recovery and compatibility

Back up the database and review locking/data compatibility before deployment. Initial DDL is transaction-compatible PostgreSQL SQL, so failures roll back the migration transaction. If a version fails, stop further rollout, inspect the actual error/history with the migration role, correct unapplied work after review, and rerun validation/migration. Never silently change an applied migration or use Flyway repair to hide checksum drift. Prefer a new forward corrective version for applied defects. Restore from a reviewed backup when forward recovery is not safe; destructive automatic down migrations are not provided.

Bootstrap and migration are separate failure boundaries. A failed fresh-volume bootstrap may leave PostgreSQL's data directory initialized without completed role provisioning; inspect it and explicitly rerun the bootstrap command after fixing its cause. Changing `.env` does not rotate passwords inside an existing volume until an intentional provisioning/rotation operation is performed. No automatic password reset or data deletion occurs during ordinary application startup.

## Runtime context and timeouts

The internal JDBC helper requires a non-null authorized UUID and an explicit transaction. Application use cases must eventually obtain tenant identity from trusted authentication/authorization, set the local context on the transaction's own connection before data access, then commit/roll back. No public tenant selector or authenticated flow exists in this task. See the ADR for the runtime-credential compromise limitation and why Java filtering alone is insufficient.

Runtime pool: maximum 4, minimum idle 0, connection acquisition 2 seconds, validation 1 second, idle timeout 60 seconds, maximum lifetime 10 minutes, no startup connection requirement. Driver connect timeout is 3 seconds and socket timeout is 5 seconds. Server statement timeout is 3 seconds, lock timeout 1 second, and idle-in-transaction timeout 10 seconds. The context statement explicitly uses a 3-second query timeout. These local bounds must be reassessed for real workloads; they are not invented performance benchmarks.

Tenant context is cleared by commit/rollback, including when a pooled connection is reused. Neither Hikari connection reset nor an application filter replaces transaction-local settings. Never use session-wide `SET app.tenant_id` or put tenant state in the connection initialization SQL.

## Verification

Run `./mvnw -B -ntp verify` in `services/control-plane` with JDK 21 and Docker available. Surefire runs HTTP security/liveness regressions; Failsafe runs `PersistenceIsolationIT` against a new disposable pgvector/PostgreSQL 17 container. Docker unavailability fails integration testing; tests are not silently skipped. Maven `package` runs unit tests for Docker image builds without requiring a Docker socket inside the build. CI `verify` runs both suites on the GitHub runner's Docker engine.

Tests provision the real roles, execute actual Flyway migrations, and use a single-connection runtime pool to prove physical connection reuse. The tenant-owned fixture exists only in test setup, not production migrations. Administrative setup connections never substitute for runtime-role security assertions. Tests cover missing/invalid context, cross-tenant reads and writes, allowed own-tenant writes, commit/rollback cleanup, role/policy alteration and migration denials, schema constraints, history validation, and process health without PostgreSQL.

`docker compose down` preserves the named volume. Run `docker compose logs --tail=100` for bounded diagnostics and avoid verbose credential/configuration dumps. Python and frontend containers receive no database credentials.
