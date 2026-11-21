# Architecture foundation

Status: logical baseline documented by accepted EAAP-000, with development scaffolding specified by EAAP-001. The human architect owns requirements, architecture, technology selection, and acceptance. Engineers implement approved specifications.

The authoritative governance is [AGENTS.md](../../AGENTS.md). Supporting rules cover [coding](../engineering/coding-standards.md), [security](../engineering/security-standards.md), [testing](../engineering/testing-strategy.md), and [Git workflow](../engineering/git-workflow.md). See the [task register](../tasks/README.md) for the repository audit.

## Technology baseline

| Area | Approved technology | Version status |
| --- | --- | --- |
| Control plane and future Tool Gateway | Java, Spring Boot, Spring Security; Spring Data JPA and Flyway reserved for future tasks | Java 21; Spring Boot 3.5.16; Maven 3.9.11 with Wrapper 3.3.4 |
| Agent runtime | Python, FastAPI, LangGraph | Python 3.12; FastAPI 0.165.0; LangGraph 1.2.24; uv 0.13.9 |
| Frontend | React, TypeScript, Vite | React 19.3.1; TypeScript 6.0.3; Vite 8.3.8; Node 22; npm |
| Persistence and retrieval storage | PostgreSQL, pgvector | PostgreSQL 17; pgvector 0.8.7; infrastructure only |
| Development and CI | Docker Compose, GitHub Actions | Concrete container tags and immutable action SHAs in configuration |

EAAP-001 introduces manifests and Python/npm lockfiles. See its [implementation notes](../tasks/EAAP-001.md) and [container versions](../../infrastructure/docker/README.md). JPA, Flyway, database access, workflows, and business features are deliberately absent at this stage. Hosted LLM APIs must use a controlled provider abstraction when approved; local GPU inference is not assumed. Alternative frameworks, databases, brokers, state libraries, and orchestration libraries require authorization.

## Logical responsibilities and ownership

These are logical boundaries, not approval for new deployments, schemas, endpoints, or service contracts. Engineering ownership follows the boundary below; team assignments remain with the architect.

| Boundary | Responsibility and ownership |
| --- | --- |
| SaaS control plane: Spring Boot | Owns trusted business use cases, business invariants, application authorization, tenant isolation, and transaction boundaries in a modular monolith. Concrete business modules require approved task specifications. |
| Agent runtime: FastAPI + LangGraph | Owns bounded graph execution, explicit state, model adapters, context budgets, tool clients, and controlled recovery. It requests business actions through the Tool Gateway and cannot grant itself permissions. |
| Frontend: React + TypeScript | Owns typed presentation, feature hooks, API clients, models, and validation. UI checks do not replace server authorization. |
| Database: PostgreSQL + pgvector | Stores relational and vector data. Application use cases own invariants and tenant authorization; infrastructure adapters own persistence access and Flyway migrations. Schema and role design require approval before implementation. |
| Tool Gateway: Spring Boot | Owns trusted enforcement of tool permissions, tenant authorization, approval checks for state-changing agent actions, and idempotency for retryable business operations. The runtime must not bypass it. This is a logical Spring Boot boundary, not a separately approved service. |
| Knowledge retrieval | Owns retrieval services and handling of untrusted knowledge. Python runtime structure separates retrieval services from graph, transport, and model adapters. Tenant-scoped access remains subject to application-owned authorization. Deployment placement, ingestion workflow, and access contracts remain undecided. |
| Observability and evaluation | Application components own correlated traces and structured events for requests, graph steps, model calls, tool calls, approvals, latency, token use, and estimated cost. Deterministic fixtures evaluate behavior without paid LLM calls. No observability vendor or evaluation framework is selected. |

## Internal dependency rules

Spring Boot modules use practical Hexagonal Architecture: API → Application → Domain. Infrastructure implements domain/application ports. Domain rules must not depend on HTTP, Spring MVC, JPA concerns, LLM SDKs, or external services. Keep invariants in the domain and transactions explicit; do not create empty interfaces just to imitate an architecture pattern.

The Python runtime separates API transport, graph definition, state, application services, provider adapters, retrieval, Tool Gateway clients, configuration, observability, and tests. Prompts and LLM output cannot make authorization decisions. Retrieved knowledge is data, not instructions.

Frontend features separate components, hooks, API clients, models, validation, and shared design-system components. Avoid components that mix transport, business rules, transformation, and presentation.

## Decisions required before affected implementation

- Versions and tooling for future dependencies; EAAP-001 resolves the initial scaffolding versions and build/package tooling only.
- Concrete business modules, API contracts, and compatibility expectations.
- Authentication approach, tenant identity propagation, authorization model, and approval lifecycle.
- Database schema, tenant isolation design, ordinary application roles, and RLS verification approach.
- Retrieval and ingestion placement, access contracts, and background execution mechanism.
- Hosted model provider selection and provider contract; operational budgets and timeouts.
- Observability/evaluation implementation and deployment/resource configuration.

These are decision requests, not approved choices. They do not block this documentation task. Stop affected implementation until the architect resolves them. No additional technology is proposed.

## Architecture decision records

The `decisions/` directory is reserved for actual ADRs. It contains no approved ADRs at EAAP-000. Record real decisions and explicit approval status when provided by the architect; never present placeholders as approved. Git does not track empty directories, so the directory becomes persistent when an actual ADR is added.
