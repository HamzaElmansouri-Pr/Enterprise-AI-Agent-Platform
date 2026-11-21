# Git workflow

This workflow inherits [AGENTS.md](../../AGENTS.md). Preserve existing repository configuration and human work.

## Before editing

Inspect working directory, repository root, branch, working-tree status, remotes, existing changes, instructions, ADRs, code, tests, and CI. Confirm modifications are safe. Explain scope, risks, and missing decisions before implementation. Do not overwrite user work or use destructive commands to hide problems.

## Branches and review

Work on the designated task branch. EAAP-000 uses `docs/eaap-000-engineering-foundation`. At inspection, the repository had `master` and `origin/master`, with no documented branching convention. This task does not establish a universal branch naming scheme.

Do not rename existing branches, change remotes, rewrite history, alter GitHub settings, force-push without explicit authorization, or merge into a protected branch without explicit instruction. EAAP-000 must not merge into the default branch.

## Validation, commit, and push

1. Keep changes small and within approved scope; synchronize documentation with actual behavior.
2. Run relevant validations and inspect the complete diff, including untracked task files.
3. Stage only task-related paths. Review the staged diff and ensure no unrelated files or secrets are included.
4. Commit verified changes using Conventional Commits. EAAP-000 requires `docs: establish engineering governance and architecture foundation`.
5. Push the designated branch to the configured GitHub remote without force. Report the actual commit SHA, remote branch, destination, and push result.

Report and repair validation failures where possible. If authentication or push fails, report the actual error and leave the task incomplete; do not claim a successful push. Clearly label any explicitly authorized checkpoint with blocked dependencies as incomplete.

## Task completion report

Include summary, significant files and responsibilities, architecture compliance, security assessment, performance assessment, exact validations and results, branch/commit/push evidence, approved deviations, known limitations, and review notes. Never fabricate evidence.

Completion requires implemented approved requirements, preserved boundaries, passing applicable tests, verified relevant controls, considered performance, updated documentation, no unexpected file changes, reviewed diff, created commit, successful push, and a complete report. Human architect acceptance remains required after engineering completion. See the [task register](../tasks/README.md).
