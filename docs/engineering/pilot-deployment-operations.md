# Pilot deployment operations

This opt-in single-host profile is separate from `./dev.sh` and evaluation. Read [ADR 0011](../architecture/decisions/0011-operational-security-and-recovery.md) and the [threat model](../security/operational-threat-model.md) first. No public deployment or paid inference is performed by these instructions automatically.

## Local synthetic rehearsal

Prerequisites: Linux, local Unix Docker endpoint, Compose supporting `!override`, OpenSSL 3 with CMS AES-GCM, Python 3.12+, available `/dev/shm`, and the pinned E5 model prepared according to [knowledge operations](knowledge-operations.md). Use an external directory that does not exist yet. Commands run from repository root; substitute absolute model path.

```sh
python3 -m deploy.scripts.pilot init-local --directory /tmp/eaap-pilot-rehearsal --model-dir /absolute/path/e5-small --port 18443
python3 -m deploy.scripts.pilot build --config /tmp/eaap-pilot-rehearsal/pilot.json
python3 -m deploy.scripts.pilot preflight --config /tmp/eaap-pilot-rehearsal/pilot.json
python3 -m deploy.scripts.acceptance --source-config /tmp/eaap-pilot-rehearsal/pilot.json --destination-directory /tmp/eaap-pilot-recovery --report /tmp/eaap-pilot-result.json
```

`init-local` deliberately creates a short-lived **self-signed local test** certificate, synthetic passwords, JWT keys, a unique project and disabled provider. It binds loopback only. The drill validates TLS using its explicit test CA, provisions synthetic users through the offline operator, uses real local embeddings, tests approvals and restores to a separate cluster. It removes only its owned containers/networks and preserves volumes and protected evidence for inspection. It never selects the developer database. Do not reuse a drill source containing data: use another new directory/project. Remove only the exact disposable projects/volumes after inspecting the result, using the names recorded by preflight; never use Docker prune or broad volume deletion.

## Real pilot preparation and ordering

An authorized operator must prepare the JSON configuration and protected secret directory outside the repository/build contexts. `init-local` is not production certificate provisioning. For a real pilot set `localTest: false`, install an unexpired certificate/key matching the actual DNS name, a trusted CA chain, and configure DNS/firewall/certificate renewal separately. Preflight verifies the chain against host trust; external hostname/chain checks remain a launch gate. Select the explicit HTTPS bind/port, HTTPS issuer, audience and key identifier. Publish no HTTP redirect port: HTTP clients cannot submit credentials to this profile.

Config keys are strict: version=1, mode=pilot, project=`eaap-pilot-` plus a unique suffix, release=40-character reviewed commit, external secretDir/modelDir/imageLock, bind=127.0.0.1 or 0.0.0.0, port=1024–65535, HTTPS issuer, audience, keyId, providerMode=disabled or hosted, localTest boolean. The profile ignores developer `.env`; do not add ad hoc environment overrides or Docker remote contexts.

Secret files: `POSTGRES_PASSWORD`, `DB_PASSWORD`, `MIGRATION_DB_PASSWORD`, `IDENTITY_OPERATOR_DB_PASSWORD`, `EMBEDDING_SECRET`, `EXECUTION_INTERNAL_SECRET`, `OPERATIONS_SECRET`, `jwt-private.pem`, `jwt-public.pem`, `tls.crt`, `tls.key`; `BOOTSTRAP_PASSWORD` only for explicit initial-user provisioning. Passwords/service secrets must be distinct 32–128 visible ASCII characters. Use generated randomness. No real credential goes in an example, command argument, checked-in file or image. The directory needs owner/group traversal only; files need owner read and optional service-group read (e.g. 0640, GID 10001), no group write or other access. PostgreSQL receives supplementary GID 10001; services use UID/GID 10001. Config and image lock should be operator-owned, not group-writable. Keep the offline backup private key elsewhere.

After checking out a clean reviewed release, run `build`, review and retain its image lock, then:

```sh
python3 -m deploy.scripts.pilot start-db --config /secure/pilot/pilot.json
python3 -m deploy.scripts.pilot migrate --config /secure/pilot/pilot.json
python3 -m deploy.scripts.pilot database-preflight --config /secure/pilot/pilot.json
# Once only; use actual UUIDs and authorized initial email, password from protected file.
python3 -m deploy.scripts.pilot bootstrap --config /secure/pilot/pilot.json --tenant-id TENANT_UUID --user-id USER_UUID --email INITIAL_EMAIL --tenant-name 'Pilot tenant'
python3 -m deploy.scripts.pilot start --config /secure/pilot/pilot.json
python3 -m deploy.scripts.pilot diagnostics --config /secure/pilot/pilot.json
```

Build locks are created exclusively; do not overwrite a release's lock. Use a new release configuration/lock for upgrades. The lock's revision identifies the source commit; a build from a dirty checkout is experimental evidence, never a released artifact. For distribution between hosts, retain registry digest/provenance and verify imported image identity; local image IDs are not a deployment registry.

Hosted mode requires `providerMode: hosted`, a file `OPENAI_API_KEY` and the exact accepted model snapshot. The optional Compose overlay supplies only runtime egress and file-delivered key. Operator preflight rejects obvious placeholder keys; validity/billing cannot be proven offline. No paid probe is used in readiness. Disabled mode returns truthful model-unavailable outcomes while authentication/retrieval/tool approvals remain available.

## Diagnosis and incident handling

Spring private port 9090: liveness tests the process; readiness tests availability state plus bounded `SELECT 1`. `/actuator/pilot` requires the operations secret and returns process-local counters/revision/uptime. It is never proxied publicly. Python `/ready` verifies startup configuration/artifacts, not external provider health; `/internal/operations` requires the execution secret. Container health failure alone does not restart a running process. `unless-stopped` restarts process crashes; operator investigation is required for unhealthy dependencies. Do not create restart loops for provider outages.

During an incident: restrict ingress, preserve authorized audit evidence, inspect bounded service status and counters, identify affected tenant scope, stop affected writes, rotate compromised credentials, validate readiness and authorization, and restore only when necessary using the recovery runbook. Notify the designated human incident owner; notification channels and jurisdictional obligations require operator policy. Do not dump environment variables, private API responses or whole database rows into tickets. Request logs contain correlation/status/duration, not bodies. Docker logs and host administrators are privileged; export only reviewed redacted evidence.

## Rotation and rollback

Coordinate an explicit maintenance window and backup first. Stop dependent services; replace protected files atomically, preserve permissions, recreate services so bind mounts see replacements, then preflight/readiness/authentication checks. Do not overwrite mounted files in place and assume all processes reload.

- JWT: generate a new RSA pair (at least 2048 bits), update keyId and files together, restart Spring; old tokens become invalid, users log in again. There is no overlapping-key JWKS rollover.
- TLS: deploy renewed CA-issued chain/key together, recreate web, verify externally with hostname and CA validation. Never disable client verification to solve certificate errors.
- Internal secrets: replace embedding secret in Spring/runtime together and execution secret in Spring/runtime together, restart both. Neither secret is an end-user identity.
- Operations secret: replace file and restart Spring; update authorized operator tooling.
- DB roles: stop dependents, use a protected administrative PostgreSQL session to alter only the intended role password, update its file, recreate corresponding service/operator and verify login/privileges. Normal application startup never changes roles. Provisioning is for new databases; do not rerun bootstrap as a password synchronizer against an existing pilot.
- Provider key: revoke at issuer, replace file, recreate runtime and validate configuration; a paid functional check requires separate explicit authorization.
- Backup recipient: distribute a new public certificate; retain old offline private keys until every corresponding archive expires. Verify a fresh encrypted backup and independent restore before retiring any key.

Rollback requires compatible application images and schema. V1–V8 remain immutable. Stop writes and take a verified backup before future migrations; do not assume a prior binary supports a newer schema. Restore to a fresh cluster and validate before switching ingress. No automatic down migration, live volume replacement or destructive reset exists.

Capacity gate: because forwarded headers are intentionally ignored, the existing login address limiter applies a shared5 attempts/minute budget to users behind the web container (20/minute global,2 password-hashing slots). Plan the small pilot accordingly. A wider deployment needs a reviewed trusted-proxy/client-address and abuse policy; do not enable arbitrary forwarded-header trust or disable the limiter to work around429 responses.
