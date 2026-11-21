# Authentication operations

Follow [ADR 0002](../architecture/decisions/0002-authentication-and-tenant-authorization.md) and [database operations](database-operations.md). No public registration or bootstrap API exists. These are local development procedures; production provisioning and coordinated abuse protection require separate review.

## Provision and migrate

Set four distinct local database passwords in ignored `.env`: POSTGRES_PASSWORD, MIGRATION_DB_PASSWORD, DB_PASSWORD, IDENTITY_OPERATOR_DB_PASSWORD. Do not put secrets in command arguments or shell history; obtain exported variables from your approved local secret source without echoing them. Compose reads `.env`, host commands do not. Never enable shell tracing or log request bodies/Authorization headers.

For an existing volume, back up and review it, then rerun administrator role provisioning before applying V2:

```sh
docker compose up -d --wait postgres
docker compose exec -T postgres sh /docker-entrypoint-initdb.d/10-platform-roles.sh
# Export MIGRATION_DB_PASSWORD from your protected environment.
docker compose build control-plane
docker compose run --rm --no-deps -e MIGRATION_DB_PASSWORD control-plane --spring.profiles.active=migration
```

The provisioning script sets/rotates the three dedicated application role credentials and removes role memberships; it never resets data. PostgreSQL initialization invokes it automatically only on an empty volume. Do not run migrations with runtime or operator credentials. Never combine migration/identity-bootstrap profiles with the persistent HTTP service.

## Development signing files

Generate a dedicated development pair outside the repository and Docker build context:

```sh
export AUTH_KEYS_DIR="$HOME/.config/eaap/dev-signing"
install -d -m 700 "$AUTH_KEYS_DIR"
(umask 077; openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$AUTH_KEYS_DIR/private.pem")
openssl pkey -in "$AUTH_KEYS_DIR/private.pem" -pubout -out "$AUTH_KEYS_DIR/public.pem"
chmod 600 "$AUTH_KEYS_DIR/private.pem" "$AUTH_KEYS_DIR/public.pem"
export AUTH_ISSUER=http://127.0.0.1:8080
export AUTH_AUDIENCE=eaap-control-plane
export AUTH_KEY_ID=dev-1
export AUTH_PRIVATE_KEY="file:$AUTH_KEYS_DIR/private.pem"
export AUTH_PUBLIC_KEY="file:$AUTH_KEYS_DIR/public.pem"
```

Host Java runs as the file-owning user. For Docker, the control-plane user/group is 10001. Grant that group access to this dedicated directory and files (review host group membership first):

```sh
sudo chgrp -R 10001 "$AUTH_KEYS_DIR"
chmod 750 "$AUTH_KEYS_DIR"
chmod 640 "$AUTH_KEYS_DIR/private.pem" "$AUTH_KEYS_DIR/public.pem"
docker compose -f compose.yaml -f compose.auth.yaml up -d --wait
```

The bind mount is read-only and not copied into images; frontend/Python receive no keys. Never use development keys in production. Protect parent directories, key backups and host group membership. Without both key paths configured, default Compose still starts healthy but login returns 503 and bearer requests are rejected. Partial or invalid configured keys fail startup. Rotation replaces the pair and key ID and restarts the process; previous tokens stop validating immediately. No overlapping rotation is provided. Use synchronized clocks because validation has zero allowed expiry skew.

## Initial tenant and owner

For local development, with PostgreSQL started and migrations applied by `./dev.sh up`, run this command directly in an interactive terminal:

```sh
./dev.sh bootstrap
```

It asks for email, tenant name, password and confirmation. Password input is hidden, length/nonblank validation happens before Docker starts, and operator database credentials are read from `.env` through an offline-only Compose override. The password is not written to a file or placed in command arguments. This helper requires Bash and Linux UUID generation. Optional exported `BOOTSTRAP_TENANT_ID`/`BOOTSTRAP_USER_ID` are retained; otherwise new IDs are generated and printed after success. Save those IDs for repeat execution, which must use the same IDs/email/name to match existing records. Do not rerun with new IDs for an existing account. Input failures identify the field/constraint without echoing its value; database errors remain sanitized. The following explicit environment-based process remains supported for operators.

Select stable tenant/user UUIDs and a nonblank tenant name of at most 200 characters. Supply all five BOOTSTRAP inputs from the operator's protected environment: BOOTSTRAP_TENANT_ID, BOOTSTRAP_TENANT_NAME, BOOTSTRAP_USER_ID, BOOTSTRAP_EMAIL, BOOTSTRAP_PASSWORD, plus IDENTITY_OPERATOR_DB_PASSWORD. Password must be nonblank and 16–128 characters; no value is defaulted. Email uses the same normalization/validation as login. Never provide password literals on the CLI.

```sh
docker compose run --rm --no-deps \
  -e IDENTITY_OPERATOR_DB_PASSWORD \
  -e BOOTSTRAP_TENANT_ID -e BOOTSTRAP_TENANT_NAME \
  -e BOOTSTRAP_USER_ID -e BOOTSTRAP_EMAIL -e BOOTSTRAP_PASSWORD \
  control-plane --spring.profiles.active=identity-bootstrap
```

The plain Compose file suffices: the operator process does not need signing keys or migration secrets. It runs no web server and uses a one-connection pool with `eaap_identity_operator`. Creation is atomic: one tenant, hashed user password, OWNER membership. Repeating the same IDs/email/name/OWNER association succeeds without changing existing records or password. Conflicting or partial data fails without writing changes; review manually rather than overwriting. Unset operator inputs/secrets and stop/remove the temporary container afterward. No user is created during normal startup. Existing account/member maintenance is outside this task and requires a reviewed operator process, never runtime-table write grants.

For host execution, export DB_URL and operator inputs, then run from services/control-plane:

```sh
./mvnw spring-boot:run -Dspring-boot.run.profiles=identity-bootstrap
```

## API and failures

Use POST `/api/v1/auth/login` with Content-Type application/json and email/password JSON read from a protected client input/file, never URL parameters. Successful response contains only accessToken, tokenType and expiresIn=900. Keep tokens in protected client storage and avoid verbose HTTP tracing. Send Authorization: Bearer for GET `/api/v1/tenants/{tenantId}/context`. The API returns only tenantId, userId and the current database role. Unknown/disabled identity or invalid token is 401; missing membership and invisible/missing registry row are indistinguishable 403; invalid UUID/input is 400; unsupported content type is 415; local limit is 429; absent signing configuration is 503; internal failures are sanitized 500.

Local limits: five attempts per direct peer IP and twenty per process per fixed minute, max two concurrent verification operations. A proxy makes clients share an address because forwarded headers are not trusted. Multiple replicas/restarts/window boundaries limit the control. A production coordinated policy is outstanding. No permissive CORS, form login or session cookie authentication is enabled. Login alone is exempt from CSRF for the stateless JSON contract; other endpoints keep default protections.

Do not enable Spring HTTP/JDBC TRACE, Hibernate bind logging, database parameter/failed-row logging, or request-body capture around credentials. Operator PostgreSQL role settings suppress sensitive SQL/error details; administrator sessions used to inspect identity data must apply equivalent controls. Logs contain correlation IDs, status and elapsed request time without credential values; investigate operational failures through reviewed diagnostics without dumping environment/configuration or identity rows.
