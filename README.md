# Enterprise AI Agent Platform

AI agent orchestration and management platform. EAAP-001 establishes development scaffolding only. The control plane and runtime expose process-health endpoints; the frontend shows static **Not connected** statuses. API connectivity, authentication, tenants, agents, workflows, LLM calls, retrieval, database entities, migrations, Tool Gateway, billing, and deployment are not implemented.

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

Follow [AGENTS.md](AGENTS.md), the [architecture foundation](docs/architecture/README.md), and [EAAP-001 notes](docs/tasks/EAAP-001.md). Exact direct dependencies are pinned in `pom.xml` and `pyproject.toml`/`package.json`; Python and npm transitive resolutions are committed in `uv.lock` and `package-lock.json`. Java dependencies/plugins use Spring Boot's pinned parent management; Maven has no native npm-style lockfile.

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

Spring permits only anonymous `GET /actuator/health`; other paths return 403. Security filters and CSRF protection remain enabled, no development user is generated, and health details/other Actuator endpoints are not exposed.

## Environment configuration

Copy `.env.example` to the ignored root `.env` and set a unique **local development** `POSTGRES_PASSWORD` before running Compose. The example intentionally has no password. Do not commit `.env` or use these development database settings in production. Compose passes the password only to PostgreSQL, not application builds or containers.

`POSTGRES_USER` and `POSTGRES_DB` default to `eaap_dev`. This is a local database bootstrap account, not a production application role. Neither application has database access code or connection settings. `APP_ENV` configures the Python environment label. Host commands read process environment; they do not automatically load the root `.env`.

`VITE_CONTROL_PLANE_API_URL` and `VITE_AGENT_RUNTIME_API_URL` are public build-time API settings, reserved for future connectivity. For host Vite, copy the two values into ignored `apps/web/.env.local` or export them in the shell. Compose supplies them as build arguments. Never put secrets in `VITE_*`; rebuilding is required after changes. The current frontend performs no API requests or polling.

## Docker Compose

```sh
cp .env.example .env
# Edit .env and set POSTGRES_PASSWORD before continuing.
docker compose config --quiet
docker compose up --build --wait
docker compose ps
docker compose logs --tail=100
docker compose down
```

Use either host servers or Compose to avoid port conflicts. All published development ports bind to `127.0.0.1`; PostgreSQL has no published host port. Services share Compose's default network and resolve each other by service name. Browser API addresses use host loopback, not Compose DNS. Apps intentionally do not depend on database startup. Data persists in the `postgres-data` named volume when containers stop; do not remove the volume unless intentionally discarding local data.

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

Java tests cover anonymous health, restricted routes, and rejected health writes. Python tests cover response/schema, hidden docs, safe settings, and lack of permissive CORS. The Vitest smoke test renders the actual React screen and checks its identity and disconnected service statuses without adding a browser-test library.

GitHub Actions runs these components independently with read-only repository permissions, immutable action SHAs, and no deployment or paid API calls. See [EAAP-001 evidence](docs/tasks/EAAP-001.md) for executed checks and limitations; a workflow definition alone is not evidence of passing CI.
