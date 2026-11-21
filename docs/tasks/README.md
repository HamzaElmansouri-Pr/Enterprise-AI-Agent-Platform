# Task register

Tasks require architect-approved scope and acceptance criteria. Engineers implement approved work and report evidence following [AGENTS.md](../../AGENTS.md) and the [Git workflow](../engineering/git-workflow.md). Human architect acceptance is separate from an engineering delivery report.

## EAAP-002 — PostgreSQL persistence and tenant isolation foundation

See [implementation and validation notes](EAAP-002.md), [ADR 0001](../architecture/decisions/0001-postgresql-tenant-isolation.md), and [database operations](../engineering/database-operations.md). The architect accepted EAAP-000 and EAAP-001 before assigning this task. Initial database role separation, schema migration, and transaction-local RLS are in scope; authentication, tenant lifecycle/administration, business APIs, and AI features remain outside it.

## EAAP-001 — Repository scaffolding and development foundation

See [scope, dependency versions, implementation decisions, and validation evidence](EAAP-001.md). The architect accepted EAAP-000 before assigning EAAP-001; its audit below remains a historical snapshot. The new task selects initial build tooling and versions, superseding that part of EAAP-000's outstanding decisions. Other application architecture decisions remain open.

## EAAP-000 — Repository audit and engineering foundation

Scope: inspect the repository and establish governance documentation without application implementations, database entities, authentication, agents, dependency manifests, or infrastructure configuration.

### Inspection before edits

- Working directory and repository root: `/home/hamza/Workspace/ELMA_Core/Enterprise AI Agent Platform`.
- Branch: `master`; initial commit: `0723c45328c4e21cd3250d9c33485eb25e8001ca` (`First commit`). Working tree clean, with no uncommitted changes.
- Remote: `origin`, fetch/push URL `https://github.com/HamzaElmansouri-Pr/Enterprise-AI-Agent-Platform.git`; local remote-tracking branch `origin/master`.
- Tracked inventory: `.gitignore`, `AGENTS.md`, `README.md`. Existing README contains only `First Commit`. The approved architect-supplied governance is already tracked and is preserved verbatim.
- `.gitignore` contains `AGENTS.md`; the file remains tracked, so this does not suppress changes to it. Preserve the existing ignore policy in this task.
- No application directories, build tools/manifests, dependency locks, tests/configurations, CI/CD workflows, or architecture documents/ADRs exist. No installed application technology versions can be inferred from this inventory.
- Approved versions: Java 21 and Spring Boot 3.x. Remaining exact versions have not been selected or pinned.
- No conflicting implementation or documented branch naming convention was found. Governance documentation can be added safely without changing existing files.

### Deliverables and boundaries

The [architecture foundation](../architecture/README.md) documents the SaaS control plane, runtime, frontend, database, Tool Gateway, retrieval, and observability/evaluation responsibilities. Engineering documents cover [coding and resource standards](../engineering/coding-standards.md), [security](../engineering/security-standards.md), [testing](../engineering/testing-strategy.md), and [Git workflow](../engineering/git-workflow.md).

The architecture `decisions/` directory is reserved for actual ADRs; no speculative or approved placeholder ADRs are created. Empty directories are not represented in Git.

### Verification and delivery requirements

Verify documentation paths and internal links, review all new content for secrets, run `git diff --cached --check`, and inspect the staged diff and path list. Confirm existing files match the initial commit and only the six requested documentation files are staged. No runtime build/test command exists yet; no application behavior or runtime performance changes in this task. No security controls are implemented or claimed tested by documentation alone.

Required branch: `docs/eaap-000-engineering-foundation`. Required commit message: `docs: establish engineering governance and architecture foundation`. Push this branch to the existing `origin` without force or merge. Record actual validation results, commit SHA, and push status in the delivery report; this document does not assert successful delivery or architect acceptance in advance.

### Outstanding decisions and next task

The architecture foundation lists unresolved implementation decisions, including versions/tooling, contracts, authentication/tenancy/approvals, schema/roles, retrieval placement, hosted providers, and operational budgets. No extra technology or deviation is proposed. Architect review of these documents and decisions is required before affected implementation.

Recommended next task, subject to architect approval: resolve build/package tooling and exact stack versions, then approve a narrowly scoped repository scaffolding task with explicit validation commands and CI acceptance criteria. Application functionality remains outside EAAP-000.
