# Local runEAAP launcher

## Summary and files

The user authorized a repository command named `runEAAP` that starts the local
project and automatically shows working login credentials and a tenant ID.
This is a local developer convenience task, not EAAP-015 or an architecture
change. Baseline was clean commit `791ec7e5eda76dd2834b90144eb4fac6eb44dee6`.

- `runEAAP`: executable standard-library Python CLI, local Docker context guard,
  private saved account/keys, startup lock, existing Compose/bootstrap lifecycle,
  frontend-proxied login and current OWNER verification before credential output.
- `infrastructure/docker/test_local_launcher.py`: deterministic positive/negative
  checks for credentials, secret transport, authorization and concurrent startup.
- `README.md`: startup, one-time command installation, saved-state behavior and
  operational limits.
- `.github/workflows/ci.yaml`: runs the new tests and scoped Ruff checks in the
  existing Python job; no additional service or dependency.
- This report and the task register record scope and executed evidence.

## Architecture, security and compatibility

No application, API, migration, dependency manifest, role or framework changes.
Uses the existing offline operator bootstrap and signing Compose override.
Bootstrap credentials enter the one-shot process via environment variable names;
password values never appear in subprocess arguments. The regular HTTP service
receives runtime database credentials and signing keys, not the identity operator
password. Existing identities/data are preserved; bootstrap matches stable IDs
and never resets a password. UUID-scoped `example.test` accounts are synthetic.

Per-checkout state is outside the repository/build contexts in
`~/.local/state/eaap/<checkout-path-hash>/`. Login JSON is owner-readable/writable
only; signing files permit only owner/container-group access. The launcher rejects
symbolic state/credential/signing/lock paths, refuses concurrent launcher runs and
remote Docker contexts, and ignores ambient Compose file/project overrides.
Docker group adjustment uses a short-lived existing application image as root,
restricted to the dedicated key directory bind mount; persistent services retain
their existing non-root configuration. Tokens/error bodies are not printed.

The requested terminal output deliberately includes the local password only after
successful authentication and matching user/tenant OWNER verification. Users must
avoid capturing that output in shared logs. Unknown failures do not echo exception
details that could carry credentials. Local credential corruption or membership
changes fail verification rather than overwrite an account.

The installed `~/.local/bin/runEAAP` symlink targets this checkout. Existing shell
configuration already included that directory on PATH; no shell files changed.
No existing command was overwritten. Moving the checkout changes the state key;
normal source instructions cover installation on another machine.

## Performance

The command delegates builds, bounded resource caps and migrations to `dev.sh up`.
It adds one offline bootstrap, one signing-container restart, one login and one
tenant-context request. No polling loops, extra application queries, caching or
paid model calls. Subprocesses have a 30-minute bound, signing startup a 180-second
health bound, Docker context inspection 15 seconds and HTTP requests 20/15 seconds.
Concurrent launcher starts are rejected before provisioning. Startup can interrupt
active local sessions while Compose recreates the API; this is development tooling.

## Validation

Executed on 2026-11-26:

```sh
python3 -m unittest discover -s infrastructure/docker -p test_local_launcher.py -v
python3 -m py_compile runEAAP infrastructure/docker/test_local_launcher.py
services/agent-runtime/.venv/bin/ruff check --config pilot/ruff.toml runEAAP infrastructure/docker/test_local_launcher.py
services/agent-runtime/.venv/bin/ruff format --check --config pilot/ruff.toml runEAAP infrastructure/docker/test_local_launcher.py
git diff --check
```

Seven tests pass: private stable synthetic credentials; symlink rejection without
touching the target; malformed saved password preservation; correct OWNER required
including wrong-role/wrong-tenant negatives; failed login stops context checks;
environment-only bootstrap secret and verified-only credential output; concurrent
startup rejection before Docker execution. Lint, format and compilation pass.

Installed command resolved to `~/.local/bin/runEAAP`. Two real `runEAAP` startups
completed successfully, each with a verified login/OWNER tenant through the actual
frontend proxy. All four services are healthy. SHA256 comparison confirmed the
saved credential file and both signing files were unchanged by the repeat run;
login JSON mode was 0600 and private-key mode 0640. A separate live check returned
200 for valid login and OWNER access, 403 for an unauthorized tenant and 401 for
an incorrect password. `DOCKER_HOST=tcp://127.0.0.1:1 runEAAP` exited 1 before any
connection/provisioning, with the expected local-only diagnostic. Protected logs
and saved credentials are outside Git. Task-file exact-password scanning and
Markdown link checks passed. Final Git evidence is supplied in the delivery response.

No full application regression rerun is claimed: application code/dependencies are
unchanged. Docker builds and the existing migration workflow ran during startup.

## Delivery, deviations and limitations

Branch: `feat/local-run-eaap-launcher`, destination: existing `origin` at
`https://github.com/HamzaElmansouri-Pr/Enterprise-AI-Agent-Platform.git`. Actual SHA
and push result are supplied in the final delivery response; no merge/force push.
No architectural deviations or new decisions. Review is required before acceptance.

Baseline knowledge ingestion and hosted AI remain opt-in; this launcher enables
local login and the existing console/API only. Its account is separate from the
previously supplied test account. Deleting saved state does not delete database
rows and is not a password recovery procedure. The launcher provisions against
this repository's dedicated local development database, never a production target.
