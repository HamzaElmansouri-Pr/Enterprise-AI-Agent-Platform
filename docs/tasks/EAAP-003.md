# EAAP-003 — Authentication and tenant membership delivery

Implementation pending human architect review. Started from accepted `origin/master` commit `7f4fb19` on `feat/eaap-003-auth-authorization`, with a clean working tree. No existing user work was overwritten. The supplied EAAP-003 task is the authority for local credential authentication, RS256 tokens, live memberships, offline bootstrap, and integration with the accepted RLS design.

## Implementation and file inventory

- `services/control-plane/.../identity/domain`: normalized bounded Email, Role and immutable TenantAccess records; independent of Spring, HTTP and persistence.
- `identity/application`: Login and TenantContext use cases, explicit credential/password/token/tenant-read ports, safe typed failure reasons. No generic permission engine.
- `identity/infrastructure`: parameterized global identity lookup functions, Spring Argon2 and Nimbus JWT adapters, transaction-bound registry read, and separate offline operator bootstrap/configuration.
- `identity/api`: bounded JSON controller, local attempt/concurrency protection, consistent sanitized errors, and correlation/status/duration logging without credentials or request bodies.
- `config/SecurityConfiguration.java`: stateless bearer authentication with current enabled-user verification, exact permitted routes, empty caller authorities, narrow login-only CSRF exception, preserved default deny.
- `V2__users_and_memberships.sql`: global users, composite tenant memberships, constraints/indexes, narrow SECURITY DEFINER functions with no PUBLIC execution, insert-only offline role grants/policies. V1 and PostgresTenantContext are unchanged.
- `application-identity-bootstrap.yaml` and application entry point: explicit no-HTTP one-shot operator mode. Normal application never creates identities; migration still uses its separate no-HTTP profile.
- `pom.xml`: Boot-managed resource-server and validation starters; supported Argon2 dependency Bouncy Castle 1.87 explicitly pinned. No major framework changes.
- PostgreSQL administrator bootstrap scripts, `.env.example`, `compose.yaml`: provision a separate distinct identity-operator credential. HTTP receives only runtime DB credentials.
- `compose.auth.yaml`: optional read-only signing files outside the build context, merged into the existing four-service stack. Ignore files exclude PEM/key material.
- `AuthenticationIT`, `IdentityUnitTest`, existing `PersistenceIsolationIT`, and CI: positive/negative authentication, JWT, membership, bootstrap and RLS verification. Existing isolation tests changed only to provision the additional offline role and expect two migration versions.
- [ADR 0002](../architecture/decisions/0002-authentication-and-tenant-authorization.md), [authentication operations](../engineering/authentication-operations.md), and linked README/security/database/task-register updates document actual boundaries and commands. Python and React source/manifests are unchanged.

## Architecture and security

Authentication establishes UUID identity; membership authorizes access to the requested tenant. Tokens contain no authoritative tenant permission. Each bearer request checks enabled state, then the exact enabled membership. Runtime cannot read global identity tables directly or mutate identities. An authorized transaction applies the unchanged PostgreSQL helper and reads the real tenant registry on the same Spring-bound connection. Forced RLS remains enabled; no runtime ownership or BYPASSRLS grants are added.

Passwords use framework Argon2id with 19 MiB memory, t=2, p=1, random 16-byte salt, 32-byte hash. JWTs use only RS256 with explicit kid/signature/issuer/audience/timestamp/UUID/jti validation and lifetime at most 900 seconds. No private keys or real credentials are tracked. Missing keys safely disable issuance and validation; incomplete configured keys fail startup. Unknown/wrong/disabled login failures are identical. JSON bodies and credential fields, local attempts, concurrent hashing, database pools and SQL timeouts are bounded.

Bootstrap uses its own operator role, no web server, no migration secrets, a single atomic transaction, and stable input UUIDs. Repeats return the matching existing triple without changing passwords/membership; conflicts fail. No registration, management API, UI, external provider, refresh token, agents, LLM calls, RAG, broker or cache is introduced.

## Local validation actually executed

With JDK 21, Docker and the pinned tooling:

```sh
cd services/control-plane
./mvnw -B -ntp verify
```

Result: **55 Java tests passed**, zero failures/errors/skips: 9 health/default-deny regressions, 4 identity/limiter/key unit tests, 29 authentication/membership/bootstrap integration cases (including parameterized token rejections), 13 existing PostgreSQL isolation cases. Both fresh migration and explicit V1→V2 upgrade passed. Tests generate ephemeral test-only RSA signing pairs in owner-only temporary files and delete them afterward. No production key or external paid API is required.

The suite exercises correct/wrong/unknown/disabled login, strict/oversized/query/duplicate JSON input, salted hashes, no password/hash response, local throttling, valid/expired/bad-signature/wrong-issuer/wrong-audience/HS256/RS512/unsigned/malformed/missing-claim/unknown-kid tokens, nonexistent/disabled UUIDs, live membership removal, signed client role claims ignored, all three approved roles, both-tenant reads, real registry visibility, invalid UUID, default deny, runtime grants, explicit same PostgreSQL backend for context/read, pooled cleanup after commit/rollback/error, safe 500, bootstrap atomicity/idempotence/missing password and real offline profile startup.

```sh
cd services/agent-runtime
uv run --locked ruff check .
uv run --locked ruff format --check .
uv run --locked pytest
```

Result: lint/format passed; **10 Python tests passed**.

```sh
cd apps/web
npm run lint
npm run typecheck
npm test
npm run build
```

Result: all passed; **1 frontend test passed**, production bundle built. Existing locked dependency installs were used locally; CI independently runs locked installation steps.

An isolated `eaap-003-verification` Compose project uses fresh throwaway passwords, a disposable volume, external test signing files and nondefault loopback ports. Actual operations verified: four services healthy; image build; no-HTTP migration; owner bootstrap and repeat; login 503 with no signing keys; configured RS256 login; OWNER context 200; unknown-tenant 403; unauthenticated context 401; no PostgreSQL published port; no migration/operator/admin secret in HTTP/Python/frontend configuration; no token/password/private-key contents in container logs. Only this explicitly disposable verification volume is removed afterward. The operator commands are documented in [authentication operations](../engineering/authentication-operations.md); no developer data is used.

Maven dependency-tree inspection verifies resolved JWT/encoder versions. Diff, unchanged V1/helper, internal documentation links, tracked secret-pattern review, and task-only staging are checked before commit. No dedicated vulnerability scanner or load benchmark is claimed. Hosted CI result, exact commit SHA, successful remote push and PR URL belong to the final delivery report; this file records local evidence and does not predeclare hosted CI success.

## Performance, limitations and review

Per successful tenant request: one enabled query, one exact membership query, one transaction-local set_config, one indexed registry read; no full membership scans, N+1, identity network service, password hashing, or stale permission cache. Runtime pool remains four, migration two, operator one. Local login allows five peer-IP attempts / twenty process attempts per minute and two concurrent hashing operations; this is not distributed production protection. No latency claims are invented.

Review focus: migration/grant boundaries and existing-volume role provisioning; external signing-file ownership; strict JWT/issuer/audience contract; deliberate ASCII email syntax; startup issuance disabled without keys; local limiter's NAT/restart/replica limitations; no overlapping key rotation; token theft until expiry/account disabling; in-flight membership-removal race; arbitrary trusted-runtime SQL remains able to assert GUCs as in ADR 0001. Production secret/TLS/abuse-control operations and future OIDC require separate approval. Human architect acceptance remains outstanding. No EAAP-004 implementation is started.
