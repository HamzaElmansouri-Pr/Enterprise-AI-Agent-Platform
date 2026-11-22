# Architecture foundation

Status: EAAP-000 logical baseline, EAAP-001 scaffolding, and EAAP-002 persistence/isolation are accepted. EAAP-003 authentication/membership is accepted. EAAP-004 agent configuration management is accepted. EAAP-005 knowledge ingestion and retrieval is accepted. EAAP-006 controlled execution is accepted. EAAP-007 adds the Tool Gateway and human approval, pending independent review. The human architect owns requirements, architecture, technology selection, and acceptance. Engineers implement approved specifications.

The authoritative governance is [AGENTS.md](../../AGENTS.md). Supporting rules cover [coding](../engineering/coding-standards.md), [security](../engineering/security-standards.md), [testing](../engineering/testing-strategy.md), and [Git workflow](../engineering/git-workflow.md). See the [task register](../tasks/README.md) for the repository audit.

## Technology baseline

| Area | Approved technology | Version status |
| --- | --- | --- |
| Control plane and future Tool Gateway | Java, Spring Boot, Spring Security, Spring Data JPA, Flyway, PostgreSQL JDBC | Java 21; Spring Boot 3.5.16; Maven 3.9.11 with Wrapper 3.3.4; Boot-managed persistence/test dependencies |
| Agent runtime | Python, FastAPI, LangGraph | Python 3.12; FastAPI 0.165.0; LangGraph 1.2.24; uv 0.13.9 |
| Frontend | React, TypeScript, Vite | React 19.3.1; TypeScript 6.0.3; Vite 8.3.8; Node 22; npm |
| Persistence and retrieval storage | PostgreSQL, pgvector | PostgreSQL 17; pgvector 0.8.7 available but not activated; tenant-identity/RLS schema via Flyway |
| Development and CI | Docker Compose, GitHub Actions | Concrete container tags and immutable action SHAs in configuration |

EAAP-001 introduced manifests and Python/npm lockfiles. See its [implementation notes](../tasks/EAAP-001.md) and [container versions](../../infrastructure/docker/README.md). EAAP-002 introduces persistence dependencies, Flyway migrations, role separation, and an internal transaction-local context helper; it introduces no JPA entities, business APIs, authentication, or workflows. See [ADR 0001](decisions/0001-postgresql-tenant-isolation.md) and [database operations](../engineering/database-operations.md). Hosted LLM APIs must use a controlled provider abstraction when approved; local GPU inference is not assumed. Alternative frameworks, databases, brokers, state libraries, and orchestration libraries require authorization.

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
- Future identity lifecycle, external OIDC integration and approval lifecycle; EAAP-003 resolves initial local authentication and tenant membership in [ADR 0002](decisions/0002-authentication-and-tenant-authorization.md).
- Future business schemas and access patterns; tenant lifecycle rules and privileged discovery/administration. EAAP-002 resolves the initial identity schema, database roles, transaction-local context, and RLS mechanism only.
- Retrieval and ingestion placement, access contracts, and background execution mechanism.
- Hosted model provider selection and provider contract; operational budgets and timeouts.
- Observability/evaluation implementation and deployment/resource configuration.

These are decision requests, not approved choices. They do not block this documentation task. Stop affected implementation until the architect resolves them. No additional technology is proposed.

## Architecture decision records

The `decisions/` directory contains [ADR 0001](decisions/0001-postgresql-tenant-isolation.md), recording the database isolation approach approved by EAAP-002 and the implementation choices awaiting independent review. See also [ADR 0002](decisions/0002-authentication-and-tenant-authorization.md) for the EAAP-003 authentication and membership boundary. Future tenant lifecycle decisions remain open.

[ADR 0003](decisions/0003-agent-configuration-domain.md) records agent configuration management, authorized transaction reuse, terminal archival, and its API/security/resource boundaries. Agent execution remains outside this delivery.

[ADR 0004](decisions/0004-knowledge-ingestion-and-retrieval.md) assigns the initial knowledge source/extraction/vector/retrieval slice to Spring Boot and only local CPU embedding computation to FastAPI. This resolves that initial placement; ingestion scaling and future RAG/agent execution contracts remain open.

[ADR 0005](decisions/0005-controlled-agent-execution.md) defines the bounded synchronous LangGraph/hosted-provider execution slice, tenant/creator-isolated run persistence, daily budgets, and validated source mapping. See [execution operations](../engineering/agent-execution-operations.md).

[ADR 0006](decisions/0006-tool-gateway-and-human-approval.md) defines one allowlisted admissions task tool, immutable proposals, different-human approval, atomic SQL effects and shared AI admission. See [tool approval operations](../engineering/tool-approval-operations.md).

[ADR 0007](decisions/0007-tenant-operations-console.md) records the secure tenant console, memory-only authentication, same-origin Spring proxy and separate human approval/execution interactions. See [console operations](../engineering/web-console-operations.md).

[ADR 0008](decisions/0008-tenant-invitations-and-access-management.md) records invite-only identity creation, narrow database membership capabilities, protected OWNER authority and Team & Access. EAAP-009 remains pending independent review; see [tenant access operations](../engineering/tenant-access-operations.md).

[ADR 0009](decisions/0009-secure-agent-conversations.md) records private conversations, run-backed message pairs, durable idempotency and bounded multi-turn LangGraph context. EAAP-010 is pending independent review; see [conversation operations](../engineering/conversation-operations.md).
