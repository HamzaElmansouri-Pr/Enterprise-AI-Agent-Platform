# Enterprise AI Agent Platform

AI agent orchestration and management platform. EAAP-001 establishes development scaffolding; EAAP-002 adds PostgreSQL role separation, Flyway tenant-identity schema, and a transaction-local RLS foundation. EAAP-003 adds local password login, short-lived RS256 tokens, current tenant-membership authorization, and offline owner bootstrap. EAAP-004 adds tenant-owned agent configuration creation, listing, retrieval, updates and archival; see [ADR 0003](docs/architecture/decisions/0003-agent-configuration-domain.md) for API contracts and [validation evidence](docs/tasks/EAAP-004.md). The APIs expose process-health, protected tenant-context and agent configuration endpoints; the frontend shows static **Not connected** statuses. Tenant lifecycle/administration APIs, JPA entity mappings, frontend API connectivity, agent execution, workflows, LLM calls, retrieval, Tool Gateway, billing, and deployment are not implemented.

## Stack and structure

- Java 21, Spring Boot 3.5.16, Maven 3.9.11 via Maven Wrapper 3.3.4.
- Python 3.12, FastAPI 0.165.0, LangGraph 1.2.24, uv 0.13.9.
- React 19.3.1, TypeScript 6.0.3, Vite 8.3.8, Node 22, npm.
- PostgreSQL 17 with pgvector 0.8.7, Docker Compose, GitHub Actions.

```text
apps/web/                     React + TypeScript application
services/control-plane/       Spring Boot application and security configuration
services/agent-runtime/       FastAPI entry point, routes, settings, and tests
infrastructure/docker/        Container versions and development operations
docs/architecture/            Logical boundaries and architecture records
docs/engineering/             Coding, security, testing, and Git standards
docs/tasks/                   Task inventory and delivery evidence
.github/workflows/ci.yaml     Independent Java, Python, and frontend checks
compose.yaml                  Four-service development environment
```

Follow [AGENTS.md](AGENTS.md), the [architecture foundation](docs/architecture/README.md), [EAAP-001 notes](docs/tasks/EAAP-001.md), and [database operations](docs/engineering/database-operations.md). Exact direct dependencies are pinned in `pom.xml` and `pyproject.toml`/`package.json`; Python and npm transitive resolutions are committed in `uv.lock` and `package-lock.json`. Java dependencies/plugins use Spring Boot's pinned parent management, including JPA, Flyway 11.7.2, PostgreSQL JDBC 42.7.11, and Testcontainers 1.21.4; Maven has no native npm-style lockfile.

## Prerequisites

For host development: **JDK** 21 (including `javac`, with `JAVA_HOME` set correctly), Python 3.12, uv 0.13.9, Node.js 22.12 or newer within major 22, and npm. Initial dependency downloads require network access. Maven is downloaded and checksum-verified by the wrapper; no global Maven install is needed. On POSIX systems install `unzip` and `sha256sum` or `shasum` so the wrapper validates the pinned ZIP archive. Commands below assume a POSIX shell; Windows can use `mvnw.cmd` for Maven.

For containers: Docker Engine and Docker Compose v2 or newer with Buildx support. No host Java/Python/Node is required for Compose. Allow build-time headroom on a 16 GB RAM development machine; configured runtime memory caps total 2 GiB, excluding Docker/build overhead.

## Start applications on the host

Run each in a separate terminal from the repository root:

```sh
cd services/control-plane
./mvnw spring-boot:run
```

```sh
cd services/agent-runtime
uv sync --locked
uv run --locked uvicorn agent_runtime.main:app --host 127.0.0.1 --port 8000 --no-server-header --no-proxy-headers
```

```sh
cd apps/web
npm ci
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

The Java server uses port 8080 (`PORT` overrides it). Python defaults to production-safe settings: debug and documentation routes are disabled, no CORS middleware is installed, and `APP_ENV` accepts only `development`, `test`, or `production`. `APP_ENV` defaults to `production`; local Compose sets `development` without enabling debug or docs. Health checks do not require PostgreSQL or credentials.

| Component | Host URL | Expected result |
| --- | --- | --- |
| Control plane | `http://127.0.0.1:8080/actuator/health` | `{"status":"UP"}` |
| Agent runtime | `http://127.0.0.1:8000/health` | `{"status":"ok","service":"agent-runtime"}` |
| Frontend dev server | `http://127.0.0.1:5173` | Minimal platform screen |
| Frontend Compose preview | `http://127.0.0.1:4173` | Built minimal platform screen |

Spring permits anonymous `GET /actuator/health` and JSON `POST /api/v1/auth/login`; tenant context and agent configuration routes require a valid bearer and live membership. OWNER/ADMIN manage agent configurations; MEMBER has read access. Unspecified paths remain denied. See [authentication operations](docs/engineering/authentication-operations.md). Security filters and CSRF protection remain enabled, no development user is generated, and health details/other Actuator endpoints are not exposed.

## Environment configuration

Copy `.env.example` to the ignored root `.env` and set four different unique **local development** passwords: `POSTGRES_PASSWORD`, `MIGRATION_DB_PASSWORD`, `DB_PASSWORD`, and `IDENTITY_OPERATOR_DB_PASSWORD`. The example intentionally has no passwords. Do not commit `.env` or use these development settings in production. The regular control plane receives only `DB_PASSWORD`; bootstrap/migration credentials are never embedded in application images. Python/frontend receive no database credentials.

`POSTGRES_USER` and `POSTGRES_DB` default to `eaap_dev`. This is the local database bootstrap account; ordinary Java runtime uses `eaap_runtime` and one-shot migrations use `eaap_migration`; offline identity bootstrap uses `eaap_identity_operator`. `DB_URL` configures the host Java JDBC URL (Compose supplies its internal PostgreSQL DNS address). Hibernate cannot create schemas, Flyway is disabled during normal startup, and the connection pool has a zero idle minimum. `APP_ENV` configures the Python environment label. Host commands read process environment; they do not automatically load the root `.env`.

`VITE_CONTROL_PLANE_API_URL` and `VITE_AGENT_RUNTIME_API_URL` are public build-time API settings, reserved for future connectivity. For host Vite, copy the two values into ignored `apps/web/.env.local` or export them in the shell. Compose supplies them as build arguments. Never put secrets in `VITE_*`; rebuilding is required after changes. The current frontend performs no API requests or polling.

## Docker Compose

```sh
cp .env.example .env
# Edit .env and set all four distinct passwords before continuing.
docker compose config --quiet
docker compose up -d --wait postgres
docker compose build control-plane
# Export MIGRATION_DB_PASSWORD from your local secret source, without echoing it.
docker compose run --rm --no-deps -e MIGRATION_DB_PASSWORD control-plane --spring.profiles.active=migration
docker compose up --build --wait
docker compose ps
docker compose logs --tail=100
docker compose down
```

Use either host servers or Compose to avoid port conflicts. All published development ports bind to `127.0.0.1`; PostgreSQL has no published host port. Services share Compose's default network and resolve each other by service name. Browser API addresses use host loopback, not Compose DNS. Process health intentionally does not depend on database startup or migration state. Data persists in the `postgres-data` named volume when containers stop; do not remove the volume unless intentionally discarding local data. Existing EAAP-001 volumes need explicit [role provisioning](docs/engineering/database-operations.md) before migration; initialization scripts never reset them automatically.

See [container operations](infrastructure/docker/README.md) for exact image versions, health checks, resource caps, and pgvector verification. Vite preview serves the built frontend for local development only; production hosting is outside this task.

## Validation and CI

```sh
cd services/control-plane
./mvnw -B -ntp verify
```

```sh
cd services/agent-runtime
uv sync --locked
uv run --locked ruff check .
uv run --locked ruff format --check .
uv run --locked pytest
```

```sh
cd apps/web
npm ci
npm run lint
npm run typecheck
npm test
npm run build
```

Java `verify` requires Docker: it covers HTTP security/liveness with an unavailable database, then agent configuration/lifecycle/concurrency, login/JWT/membership/bootstrap and Flyway/schema/role/RLS behavior using a disposable Testcontainers PostgreSQL instance. No development data is used. The migration and isolation design is recorded in [ADR 0001](docs/architecture/decisions/0001-postgresql-tenant-isolation.md). Python tests cover response/schema, hidden docs, safe settings, and lack of permissive CORS. The Vitest smoke test renders the actual React screen and checks its identity and disconnected service statuses without adding a browser-test library.

GitHub Actions runs these components independently with read-only repository permissions, immutable action SHAs, and no deployment or paid API calls. See [EAAP-001 evidence](docs/tasks/EAAP-001.md) and [EAAP-002 evidence](docs/tasks/EAAP-002.md) for executed checks and limitations; a workflow definition alone is not evidence of passing CI.
