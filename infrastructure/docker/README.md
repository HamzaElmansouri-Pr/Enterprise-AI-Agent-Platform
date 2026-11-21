# Development containers

The root [Compose file](../../compose.yaml) runs four development services. Application Dockerfiles live beside their source. This adds no infrastructure beyond the approved stack.

| Purpose | Concrete base image |
| --- | --- |
| Java build | `eclipse-temurin:21.0.13.1_2-jdk-jammy` |
| Java runtime | `eclipse-temurin:21.0.13.1_2-jre-jammy` |
| Python build/runtime | `python:3.12.16-slim-bookworm` |
| Frontend build/preview | `node:22.23.5-bookworm-slim` |
| Database | `pgvector/pgvector:0.8.7-pg17-trixie` |

These concrete tags were verified against registry manifests during EAAP-001. No `latest` tag is used. Locally built application images are named `eaap-control-plane:0.0.1-dev`, `eaap-agent-runtime:0.0.1-dev`, and `eaap-web:0.0.1-dev`; they are not published. Rebuild deliberately to consume changes to tag contents; image digests may be added when an update policy is approved. Python's builder installs uv 0.13.9 and synchronizes the committed lock without dev dependencies. The Java builder runs tests using the checksum-verified Maven Wrapper; npm builds from its lock. Java runtime includes curl solely for its health check. The frontend retains Vite's preview dependencies to avoid introducing an unapproved server; it is not a production hosting configuration.

| Service | Published host port | Container port | Memory cap | CPU cap |
| --- | --- | --- | --- | --- |
| control-plane | `127.0.0.1:8080` | 8080 | 768 MiB | 1 |
| agent-runtime | `127.0.0.1:8000` | 8000 | 512 MiB | 1 |
| web | `127.0.0.1:4173` | 4173 | 256 MiB | 0.5 |
| postgres | None | 5432 | 512 MiB | 1 |

Runtime limits total 2 GiB; builds and the Docker engine need additional memory. The JVM heap is limited to 70% of its container memory, leaving native overhead. PostgreSQL shared memory is capped at 128 MiB. Java's Hikari pool has maximum 4 and minimum idle 0; startup and process health do not create database connections. No GPU, AI model, background worker, or application polling is started. Periodic container health probes are intentionally lightweight and bounded.

Applications run as non-root users, drop capabilities, and use `no-new-privileges`. PostgreSQL uses the official image entrypoint's initialization and privilege drop to the database user; it needs its normal initialization permissions. The two administrator bootstrap files are mounted read-only into PostgreSQL. No privileged containers or Docker socket mounts are configured. Build contexts exclude local configuration and generated output.

Run `./dev.sh up` from the repository root to prepare an ignored `.env` and fill missing or blank passwords, provision PostgreSQL, migrate offline, and start the four services. Existing nonempty `.env` values and volumes are preserved. The script synchronizes dedicated role credentials with `.env` on each explicit local startup. Alternatively, copy root `.env.example` to ignored `.env` and set four different unique local passwords for bootstrap, migration, runtime, and the identity operator. Compose fails configuration validation when passwords are absent; the bootstrap script rejects equal passwords. A named volume preserves database data; the PostgreSQL entrypoint and mounted role bootstrap execute automatically only for a new data directory. Changing `.env` does not change credentials in an existing volume. Do not remove existing data to work around credential mismatches. Follow [database operations](../../docs/engineering/database-operations.md) for existing-volume provisioning and the explicit one-shot migration command.

`docker compose up --build --wait` checks Java `/actuator/health`, Python `/health`, the web root, and PostgreSQL readiness. Java is configured for the runtime role at Compose's `postgres` DNS address; the migration profile is invoked in a temporary control-plane container with explicitly supplied migration credentials. Python/frontend receive no database credentials. A database outage must not change either process-health response. The regular Java service never executes migrations or startup metadata/health queries; it does not yet implement business persistence use cases.

Verify the available PostgreSQL extension without creating application schemas or enabling it in a database:

```sh
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT name, default_version FROM pg_available_extensions WHERE name = '\''vector'\'';"'
```

Expected extension: `vector`, version `0.8.7`. This inspects availability only; EAAP-002 does not activate the extension or create vector/business tables. See [ADR 0001](../../docs/architecture/decisions/0001-postgresql-tenant-isolation.md) for the new tenant-identity schema and RLS foundation. Inspect logs with `docker compose logs --tail=100`. Stop with `docker compose down`, which preserves the named volume. No restart policy hides startup failures.

`DB_HOST`, `DB_PORT`, `POSTGRES_DB`, and `DB_PASSWORD` in `.env` configure the Java runtime connection. Defaults point to `postgres:5432` on the private Compose network. `compose.migration.yaml` is an offline override used only for the temporary migration process; it supplies migration credentials and forces the no-HTTP migration profile.
