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

Runtime limits total 2 GiB; builds and the Docker engine need additional memory. The JVM heap is limited to 70% of its container memory, leaving native overhead. PostgreSQL shared memory is capped at 128 MiB. No GPU, AI model, background worker, application polling, or application database pool is started. Periodic container health probes are intentionally lightweight and bounded.

Applications run as non-root users, drop capabilities, and use `no-new-privileges`. PostgreSQL uses the official image entrypoint's initialization and privilege drop to the database user; it needs its normal initialization permissions. No privileged containers, host mounts, or Docker socket mounts are configured. Build contexts exclude local configuration and generated output.

Copy root `.env.example` to ignored `.env` and set a unique local password. Compose fails configuration validation if the password is absent. A named volume preserves database data; the bootstrap user/password only initialize a new data directory. Changing `.env` does not change credentials in an existing volume. Do not remove existing data to work around credential mismatches.

`docker compose up --build --wait` checks Java `/actuator/health`, Python `/health`, the web root, and PostgreSQL readiness. Services communicate on the default Compose network, using service names when future integration is approved. No application depends on or queries PostgreSQL. A database outage must not change either process-health response.

Verify the available PostgreSQL extension without creating application schemas or enabling it in a database:

```sh
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "SELECT name, default_version FROM pg_available_extensions WHERE name = '\''vector'\'';"'
```

Expected extension: `vector`, version `0.8.7`. This inspects availability only; extension activation, migrations, tenancy, RLS, and business tables remain future approved work. Inspect logs with `docker compose logs --tail=100`. Stop with `docker compose down`, which preserves the named volume. No restart policy hides startup failures.
