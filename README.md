# Enterprise AI Agent Platform

AI agent orchestration and management platform. The Spring control plane provides tenant-authorized agents, knowledge ingestion/retrieval, controlled LangGraph question answering and human-approved tool execution. EAAP-008 adds the functional React tenant operations console: login, tenant verification, agents, documents/search, single-turn execution, proposals/approvals and follow-up tasks. See [console setup and walkthrough](docs/engineering/web-console-operations.md) and [ADR 0007](docs/architecture/decisions/0007-tenant-operations-console.md). Tenant administration, public signup, external business integrations, billing and production hosting remain outside scope.

## Run the complete local project

With Docker Engine, the modern Compose plugin, and Buildx installed, verify:

```sh
docker info
docker compose version
docker buildx version
```

Legacy `docker-compose` v1 does not support this workflow. If `docker compose` or `docker buildx` is missing, follow Docker's [Compose plugin installation](https://docs.docker.com/compose/install/linux/) and [Buildx installation](https://github.com/docker/buildx#installing) instructions. User-local Linux plugin binaries belong in `~/.docker/cli-plugins/`, must be executable, and should be verified against release checksums. Manual installations require explicit updates.

For the baseline stack, run from the repository root:

```sh
./dev.sh up
```

This prepares an ignored, owner-readable `.env`, filling missing or blank database passwords with random distinct values, starts PostgreSQL, builds Java, provisions the dedicated database roles, applies Flyway migrations in a temporary offline container, and starts all four services. Existing nonempty `.env` values and database data are preserved. On subsequent runs, operator provisioning synchronizes dedicated role passwords with `.env`; the regular HTTP service still receives only runtime credentials and never performs migrations. Run the script only against this project's dedicated local development database.

Database connection settings live in `.env`: `DB_HOST=postgres`, `DB_PORT=5432`, `POSTGRES_DB=eaap_dev`, and `DB_PASSWORD` for the fixed `eaap_runtime` role. PostgreSQL runs as a service in the same Compose project with persistent storage and no host port. The other three passwords serve separate provisioning, migration and identity-operator boundaries.

Open the frontend at http://127.0.0.1:4173 and Java health at http://127.0.0.1:8080/actuator/health. Use `./dev.sh logs` for bounded logs and `./dev.sh down` to stop while preserving database data. `./dev.sh init` only prepares `.env`. After startup, `./dev.sh bootstrap` interactively creates the first tenant/owner using a hidden password prompt and the offline operator profile. Signing keys and an initial owner are configured separately through [authentication operations](docs/engineering/authentication-operations.md); login remains disabled until signing keys are supplied.

Knowledge processing is opt-in: prepare the pinned local model and internal secret, then use `compose.knowledge.yaml` together with signing configuration. Follow the [exact knowledge setup commands](docs/engineering/knowledge-operations.md); baseline startup keeps the worker disabled.

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
| Frontend dev server | `http://127.0.0.1:5173` | Tenant operations login |
| Frontend Compose preview | `http://127.0.0.1:4173` | Built tenant operations console |

Spring permits anonymous `GET /actuator/health` and JSON `POST /api/v1/auth/login`; tenant context and agent configuration routes require a valid bearer and live membership. OWNER/ADMIN manage agent configurations; MEMBER has read access. Unspecified paths remain denied. See [authentication operations](docs/engineering/authentication-operations.md). Security filters and CSRF protection remain enabled, no development user is generated, and health details/other Actuator endpoints are not exposed.

## Environment configuration

Copy `.env.example` to the ignored root `.env` and set four different unique **local development** passwords: `POSTGRES_PASSWORD`, `MIGRATION_DB_PASSWORD`, `DB_PASSWORD`, and `IDENTITY_OPERATOR_DB_PASSWORD`. The example intentionally has no passwords. Do not commit `.env` or use these development settings in production. The regular control plane receives only `DB_PASSWORD`; bootstrap/migration credentials are never embedded in application images. Python/frontend receive no database credentials.

`POSTGRES_USER` and `POSTGRES_DB` default to `eaap_dev`. This is the local database bootstrap account; ordinary Java runtime uses `eaap_runtime` and one-shot migrations use `eaap_migration`; offline identity bootstrap uses `eaap_identity_operator`. `DB_URL` configures the host Java JDBC URL (Compose supplies its internal PostgreSQL DNS address). Hibernate cannot create schemas, Flyway is disabled during normal startup, and the connection pool has a zero idle minimum. `APP_ENV` configures the Python environment label. Host commands read process environment; they do not automatically load the root `.env`.

The browser uses relative `/api/v1/` paths through Vite's fixed Spring proxy: host development to `127.0.0.1:8080`, web container to `control-plane:8080`. No browser API URL or secret is required. Tokens stay in memory; reload requires login. Old VITE API URL settings are unused. See [console operations](docs/engineering/web-console-operations.md).

## Docker Compose

```sh
cp .env.example .env
# Edit .env and set all four distinct passwords before continuing.
docker compose config --quiet
docker compose up -d --wait postgres
docker compose build control-plane
# Administrator-only pgvector provisioning is required before V4.
docker compose exec -T postgres sh -c 'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -f /opt/eaap/knowledge-extension.sql'
# Export MIGRATION_DB_PASSWORD from your local secret source, without echoing it.
docker compose run --rm --no-deps -e MIGRATION_DB_PASSWORD control-plane --spring.profiles.active=migration
docker compose up --build --wait
docker compose ps
docker compose logs --tail=100
docker compose down
```

Use either host servers or Compose to avoid port conflicts. All published development ports bind to `127.0.0.1`; PostgreSQL has no published host port. Services share Compose's default network and resolve each other by service name. Browser requests stay on the web origin; the web proxy reaches Spring using Compose DNS. Process health intentionally does not depend on database startup or migration state. Data persists in the `postgres-data` named volume when containers stop; do not remove the volume unless intentionally discarding local data. Existing EAAP-001 volumes need explicit [role provisioning](docs/engineering/database-operations.md) before migration; initialization scripts never reset them automatically.

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

Java `verify` requires Docker: it covers HTTP security/liveness with an unavailable database, then agent configuration/lifecycle/concurrency, login/JWT/membership/bootstrap and Flyway/schema/role/RLS behavior using a disposable Testcontainers PostgreSQL instance. No development data is used. The migration and isolation design is recorded in [ADR 0001](docs/architecture/decisions/0001-postgresql-tenant-isolation.md). Python tests cover response/schema, hidden docs, safe settings, and lack of permissive CORS. Vitest exercises login, tenant isolation in UI state, feature interactions and approval safeguards using test-only HTTP fixtures and jsdom.

GitHub Actions runs these components independently with read-only repository permissions, immutable action SHAs, and no deployment or paid API calls. See [EAAP-001 evidence](docs/tasks/EAAP-001.md) and [EAAP-002 evidence](docs/tasks/EAAP-002.md) for executed checks and limitations; a workflow definition alone is not evidence of passing CI.

Controlled agent execution (EAAP-006): [local provider setup, API checks and failure diagnosis](docs/engineering/agent-execution-operations.md). Hosted execution is opt-in; no API key is needed for startup or automated tests.

Trusted tool approval (EAAP-007): [offline two-account proposal, review and task execution](docs/engineering/tool-approval-operations.md). Manual proposals need no paid model; self-approval is prohibited.
