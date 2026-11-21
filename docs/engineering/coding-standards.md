# Coding and resource standards

These rules inherit [AGENTS.md](../../AGENTS.md); they do not replace its authority or select new technologies. See [architecture boundaries](../architecture/README.md), [security standards](security-standards.md), and [testing strategy](testing-strategy.md).

## Scope and implementation

Implement only approved functionality. Preserve existing work, module boundaries, public contracts, and established conventions. Do not add unrelated refactoring, speculative infrastructure, or repository-wide formatting. Inspect approved ADRs before making choices. A local, low-risk, reversible choice may follow existing convention and must be documented. Security, behavior, schema, infrastructure, API compatibility, and major dependency decisions require architectural direction when unspecified.

Use descriptive names, focused methods, explicit types, cohesive modules, and pragmatic SOLID principles. Prefer composition, avoid duplicate business rules and speculative abstractions, and make invariants explicit. Use typed DTOs and boundary validation, consistent error handling, explicit transaction boundaries, and structured logging with correlation IDs. Do not hide failures behind broad exception handlers. Comments explain intent and tradeoffs; document public APIs and major decisions.

## Architecture and database discipline

Follow the Spring Boot, runtime, and frontend separation documented in the architecture foundation. Keep domain rules independent of transport, persistence, and external providers. Do not scatter invariants across controllers or infrastructure.

Use Flyway migrations, explicit constraints, appropriate foreign keys, and tenant scoping in relevant entities and queries. Never rely on automatic production schema generation, modify an applied production migration, silently change existing data, or weaken database security for tests. Review migration locking, backward compatibility, integrity, and rollback/recovery; test migrations against PostgreSQL.

Use the approved stack only. Pin exact dependency versions in manifests and architecture records when approved. Major upgrades or substitutions require authorization. Do not introduce a formatter, linter, package manager, or other dependency here without a defined implementation decision.

## Performance and resource efficiency

- Avoid N+1 queries, excessive database round trips, unbounded result sets, and unbounded memory use. Paginate where appropriate and select indexes from access patterns.
- Keep long-running ingestion and embedding work off request threads. The execution mechanism must be approved before implementation.
- Apply external API timeouts and bounded retries. Bound concurrency, graph steps, context size, tokens, and tool calls.
- Measure relevant latency and resource metrics. Use profiling, query analysis, or benchmarks before adding complex optimizations.
- Define invalidation and consistency before adding caching.
- Keep development infrastructure usable on a 16 GB RAM machine; do not assume GPU availability.

Each implementation report must assess relevant query, memory, latency, and resource impacts. Numerical budgets and infrastructure choices remain architect decisions; no thresholds are invented by this document.
