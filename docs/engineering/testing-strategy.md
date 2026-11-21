# Testing strategy

This strategy inherits [AGENTS.md](../../AGENTS.md) and supports the [architecture](../architecture/README.md) and [security standards](security-standards.md).

## Behavior coverage

Select tests appropriate to the change: domain unit tests, application use-case tests, API/controller tests, database integration tests, authorization and cross-tenant isolation tests, Tool Gateway contract tests, agent workflow tests, negative/error paths, and regressions. Security-sensitive changes require positive and negative cases.

Use PostgreSQL integration tests and Testcontainers where appropriate. Verify Flyway migrations, constraints, tenant access, ordinary application role behavior with RLS, and integrity for affected database work. Include migration compatibility and recovery review.

Mock external LLM providers in deterministic tests. Cover explicit graph states, bounded execution, budgets, tool limits, approvals, provider failures, recovery, and untrusted retrieval handling where applicable. Live paid LLM calls must not be a default test prerequisite. Agent evaluation uses deterministic fixtures and does not treat generated output as verified truth.

## Validation selection and evidence

Run applicable formatting/linting, static analysis, unit tests, integration tests, builds, security/dependency checks, performance checks, and diff review. Do not invent commands before build tools exist. Record exact commands and actual results in the task report; for a test that cannot run, explain why and give its exact command when tooling defines one. Never claim unexecuted checks passed, or weaken/delete tests to make a build pass.

For documentation-only tasks, verify intended paths, internal links, scope, diff whitespace, and absence of introduced secrets. Review that application files and unrelated work are unchanged. Runtime tests, builds, and performance benchmarks do not apply to EAAP-000 because no application, manifests, or test tools exist.

No test runner, linter, scanner, or CI workflow is introduced by this task. Implementation tasks must define applicable tooling and commands under approved decisions. GitHub Actions is the approved CI platform; workflow configuration remains future work.
