# EAAP-001 — Repository scaffolding and development foundation

The architect accepted EAAP-000 and supplied this task's stack, component boundaries, verification, and Git requirements. This document records implementation choices within that scope, not additional approved ADRs. Independent review is required before acceptance.

## Repository inspection and scope

The starting working tree was clean on `docs/eaap-000-engineering-foundation`. Fetching the existing `origin` revealed EAAP-000 merged into `master` at `4f31ea1`; its tree matched the accepted documentation branch. The task branch `chore/eaap-001-project-scaffolding` was created from that merge. No existing application, build configuration, tests, CI, or unrelated user edits existed. The supplied `AGENTS.md` is preserved verbatim; its ignore entry is removed as explicitly approved.

The implementation adds `apps/web`, `services/control-plane`, `services/agent-runtime`, container operations under `infrastructure/docker`, root Compose/environment/editor/Git hygiene, and three independent CI jobs. Existing governance documents are preserved and their current version/status references synchronized. No business modules, authentication, database access, migrations, Tool Gateway, workflows, retrieval, providers, or new infrastructure services are implemented.

## Dependency record

| Component | Direct dependencies and tooling |
| --- | --- |
| Java | Java 21; Spring Boot parent/starters/plugin 3.5.16; Web MVC, Security, Actuator, and Test starters; Maven 3.9.11, Wrapper 3.3.4. Spring Boot's parent pins transitive dependencies, JUnit 5, and build plugins. |
| Python runtime | Python 3.12; FastAPI 0.165.0; LangGraph 1.2.24; Pydantic 2.14.4; Uvicorn 0.54.0; uv 0.13.9. Pydantic defines the response schema; Uvicorn runs ASGI. LangGraph is installed but unused. |
| Python development | pytest 9.2.0; Ruff 0.17.6; HTTPX 0.28.1 for FastAPI's test client. |
| Frontend runtime | React and React DOM 19.3.1. |
| Frontend development | TypeScript 6.0.3; Vite 8.3.8; plugin-react 6.1.2; Vitest 5.1.1; ESLint 10.15.0; @eslint/js 10.0.1; typescript-eslint 8.73.0; eslint-plugin-react-hooks 7.1.1; eslint-plugin-react-refresh 0.5.8; globals 17.14.0; @types/node 22.20.5; @types/react and @types/react-dom 19.3.0. |

All Python/npm direct versions are exact pins; `uv.lock` and `package-lock.json` capture transitive resolutions. Maven uses its pinned parent/BOM and wrapper properties rather than an invented lockfile. The Maven ZIP checksum was calculated only after validating the archive against Maven Central's SHA-512. POSIX builds require `unzip` to avoid the wrapper's automatic tarball fallback against the ZIP checksum.

The starting point for web was the official React TypeScript template from create-vite 9.3.4. Demo assets, sample styling, and Oxlint were removed. The template's TypeScript 7.0.2 was incompatible with typescript-eslint 8.73.0 (peer range below 6.1); TypeScript was unspecified in the task, so compatible 6.0.3 was pinned without changing any mandated version or bypassing dependency checks. Strict mode is explicit in both application and tooling configurations. The Vitest smoke test renders React with React DOM's server renderer, avoiding an extra test framework or DOM simulation dependency.

## Security and architecture decisions within scope

- Spring has a real security filter chain: only `GET /actuator/health` is permitted; all other requests are denied. No form/basic authentication or generated user is configured. CSRF remains enabled. Health exposes status only, and Actuator discovery/other endpoints are hidden.
- Spring's only application classes are the entry point and security configuration. Future modules can adopt the documented Hexagonal boundaries without empty scaffolding layers.
- FastAPI separates entry point, routes/schema, environment configuration, and tests. Debug and docs are disabled in every environment. Invalid environment labels fail startup. No CORS middleware, model/provider initialization, tools, database queries, or network calls occur in the health handler.
- Frontend markup is semantic and statuses remain disconnected. Public environment API settings are reserved for future connectivity; no API clients or polling are invented. No credentials belong in `VITE_*` values.
- Application containers run non-root with dropped capabilities and no privilege escalation. Loopback host bindings and no PostgreSQL host port limit development exposure. PostgreSQL retains its image's initialization/privilege-drop behavior.
- `.env.example` intentionally has no database password. Compose requires one; test execution uses a randomly generated ignored temporary environment file outside the repository. PostgreSQL is infrastructure-only, with no application connection credentials passed to either API.
- GitHub Actions uses read-only contents permission, immutable official action SHAs, disabled persisted checkout credentials, bounded job timeouts, and independent component checks. No deployment, production credentials, or AI API calls are configured.
- Root ignore rules exclude credentials, environments, caches, dependencies, and build output. Git attributes preserve executable shell-script line endings and the Windows wrapper's CRLF convention.

## Performance and container operations

See [concrete images, ports, health checks, and resource caps](../../infrastructure/docker/README.md). Runtime caps total 2 GiB plus engine/build overhead on the 16 GB target machine. No benchmark is invented for this scaffolding. No GPU, model, application polling, background worker, cache, or database connection pool is started. Container health probes have bounded timeouts and intervals. The Vite preview container is for local development only; production hosting remains undecided.

## Verification commands and evidence

Commands below run from each component's directory unless otherwise specified. Final Git/remote evidence is reported in the delivery report so this file does not claim a future commit or CI result in advance.

| Check | Command | Result |
| --- | --- | --- |
| Java build and security | `./mvnw -B -ntp verify` | 8 tests pass; executable JAR built with JDK 21. |
| Python lock | `uv sync --locked` | Synchronized successfully on Python 3.12. |
| Python lint | `uv run --locked ruff check .` | Pass. |
| Python format | `uv run --locked ruff format --check .` | Pass. |
| Python tests | `uv run --locked pytest` | 10 tests pass. |
| Frontend clean install | `npm ci` | Pass; npm reports no known vulnerabilities at execution time. |
| Frontend lint | `npm run lint` | Pass. |
| Frontend types | `npm run typecheck` | Pass with strict mode. |
| Frontend smoke | `npm test` | 1 test passes. |
| Frontend production build | `npm run build` | Pass. |
| Process startup | `java -jar target/control-plane-0.0.1-SNAPSHOT.jar`; `uv run --locked uvicorn agent_runtime.main:app`; `npm run dev` | Real processes started on temporary loopback ports; both health bodies matched exactly, web HTML served, and Java unrelated route returned 403. Test processes stopped afterward. |

Execution host accommodations: JDK 21.0.13.1+2, Node 22.23.5, uv 0.13.9, Compose 5.8.0, and Buildx 0.39.1 were installed under `/tmp` as needed, without modifying global tooling. Maven cache/user home were isolated under `/tmp`. Local Java commands used `JAVA_HOME=/tmp/eaap-001-jdk`, `MAVEN_USER_HOME=/tmp/eaap-001-maven`, `-Dmaven.repo.local=/tmp/eaap-001-m2`, and `DEBUG=false`; Node commands prepended `/tmp/eaap-001-node/bin` to `PATH`; uv used `UV_CACHE_DIR=/tmp/eaap-001-uv-cache`. Java testing requires running outside this environment's sandbox because Mockito's test agent cannot attach inside it. A sandboxed FastAPI test rerun stalled and was interrupted; the final `uv run --locked pytest` completed outside the sandbox with all 10 tests passing. No production security setting or assertion was weakened for these limitations.

Container validation ran from the repository root with `DOCKER_CONFIG=/tmp/eaap-001-docker-config docker compose --env-file /tmp/eaap-001-compose.env` as the Compose command prefix:

- `config --quiet`: passed. Missing database password is a configuration error by design.
- `build`: all three multi-stage application images built successfully; Java's image build also ran all 8 tests.
- `up --no-build --wait --wait-timeout 120`: all four services reached healthy status.
- `python3 /tmp/eaap-001-verify-compose.py`: passed real HTTP response/negative security checks, all health states, zero automatic restart counts, non-root application users, loopback bindings, absence of a database host port, and configured memory limits. This temporary validation script inspects only this task's containers and performs the read-only SQL checks below.
- `exec -T postgres` with `SHOW server_version` and a `pg_available_extensions` query: PostgreSQL **17.12** and pgvector **0.8.7** available. No extension was activated and no business tables or schemas were created.
- `stop postgres`, HTTP requests to both APIs, then `start --wait --wait-timeout 60 postgres`: both process-health endpoints remained healthy while the database was stopped. `pg_control_system()` returned the same cluster identifier before/after restart, confirming persistence.
- `logs --no-color`: inspected; no startup ERROR, Traceback, FATAL, PANIC, or Exception markers.
- `down`, then removal of only `eaap-dev_postgres-data`: cleaned task-created verification resources after the persistence test. No project containers/volumes existed before verification; no pre-existing user data was removed. Normal development `down` continues to preserve its database volume.

The initial container build rejected a ZIP checksum when Maven Wrapper selected a tarball because `unzip` was absent. Installing `unzip` in the builder fixed archive selection without disabling integrity verification. All final image builds passed. Local and remote CI evidence remains distinct: actual GitHub checks and PR status are reported after push, not inferred from local passes.

## Limitations and future decisions

Application security, contracts, tenant propagation, database roles/schema/RLS, approval semantics, providers, retrieval/ingestion placement, production deployment, and operational budgets remain future architectural work. No new architectural approval is required for this scaffolding's documented local choices. Independent review must assess implementation and actual CI evidence before acceptance. EAAP-002 is not started.
