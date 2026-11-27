# Backup and disaster recovery

Scope: PostgreSQL 17, pgvector 0.8.7, Flyway V9 with reviewed V8 archive compatibility. [ADR 0011](../architecture/decisions/0011-operational-security-and-recovery.md) explains the encryption/ownership model. These commands never target development Compose. Restore is an administrative action for trusted archives, not an API capability.

## Key custody and backup

Keep an independent RSA backup recipient private key offline, protected and recoverable by authorized operators. Do not reuse JWT/TLS keys. Generate it in protected storage, with restrictive umask, separately from the application secrets. The public certificate is sufficient for export; the private key is needed only for validation/recovery. Example for a **synthetic local rehearsal**:

```sh
umask 077
mkdir /secure/offline-backup-recipient
openssl req -x509 -newkey rsa:3072 -nodes -days 365 -subj '/CN=EAAP backup recipient' -keyout /secure/offline-backup-recipient/recipient.key -out /secure/offline-backup-recipient/recipient.crt
python3 -m deploy.scripts.backup create --config /secure/pilot/pilot.json --certificate /secure/offline-backup-recipient/recipient.crt --output /secure/backups
```

Use approved independent key custody for a real pilot. The tool verifies protected regular key/certificate files, but does not implement a hardware keystore or key escrow. Never upload private keys or database archives to CI. Save ciphertext and nonsecret metadata to independently controlled, encrypted off-host storage; transport, scheduling, access reviews and off-host retention remain operator responsibilities. A backup on the same disk as PostgreSQL does not cover host loss.

The exporter performs preflight, executes bounded `pg_dump -Fc`, packages a manifest with release/schema/vector/cluster identity and dump SHA-256, then encrypts using OpenSSL CMS AuthEnvelopedData AES-256-GCM and RSA-OAEP/SHA-256. Output is a unique `.cms` plus a protected `.json` with ciphertext hash/size/time/versions/duration. Existing archives cannot be overwritten. Failed exports/encryption leave no published backup. Do not schedule concurrent DDL/migrations during export. The database snapshot is consistent; external model assets, image files and protected configuration are separate recovery dependencies.

Plaintext export/decryption uses a fresh 0700 `/dev/shm` directory and 0600 files, never ordinary disk. The selected `--temporary` path must actually be Linux tmpfs. Check available memory/storage before export. The small pilot limit is 1 GiB, so provision headroom for dump, tar and ciphertext processing; do not use this tooling for an unmeasured large database. tmpfs can be swapped by the host: use encrypted swap or disable swap under approved host policy. Normal exit/error cleans temporary files. SIGKILL/host crash cannot guarantee application cleanup: after isolating the incident, inspect and remove only owned `eaap-backup-*` remnants in the selected tmpfs. No plaintext is intentionally persisted to a database container volume outside the actual restored database.

## Validate before declaring a backup usable

```sh
python3 -m deploy.scripts.backup validate --archive /secure/backups/SELECTED.cms --certificate /secure/offline-backup-recipient/recipient.crt --key /secure/offline-backup-recipient/recipient.key
```

This authenticates ciphertext, checks exact manifest compatibility and the plaintext dump digest; it does not prove the database will restore. SHA-256 alone is not authentication. Public-certificate holders can create encrypted archives, so retain provenance and operator-controlled archive selection. A real independent restore is mandatory for recovery confidence.

## Restore to a fresh independent cluster

Prepare a new external pilot config, unique project/volume and fresh role/service/JWT/TLS credentials, with the same reviewed application images/model. The config cannot name `eaap-dev`. Do not copy a live PostgreSQL directory, point at the source cluster, or overwrite a nonempty destination.

```sh
python3 -m deploy.scripts.pilot preflight --config /secure/recovery/pilot.json
python3 -m deploy.scripts.pilot start-db --config /secure/recovery/pilot.json
python3 -m deploy.scripts.backup restore --config /secure/recovery/pilot.json --archive /secure/backups/SELECTED.cms --certificate /secure/offline-backup-recipient/recipient.crt --key /secure/offline-backup-recipient/recipient.key
python3 -m deploy.scripts.pilot migrate --config /secure/recovery/pilot.json
python3 -m deploy.scripts.pilot database-preflight --config /secure/recovery/pilot.json
python3 -m deploy.scripts.pilot start --config /secure/recovery/pilot.json
```

Do **not** run migration before restoring. `start-db` runs accepted role/schema bootstrap and installs exact vector in `extensions`; the database must still contain no non-extension user objects or unexpected schemas. Only PostgreSQL may be running. Restore first authenticates fully, rejects incompatible manifests/source cluster/project/nonempty target/unsafe roles, and uses `pg_restore --single-transaction --exit-on-error`. It filters only already-created schemas and the vector extension; table/function owners, grants, forced RLS, policies, data, vector indexes and Flyway history remain in the archive. Global role passwords are deliberately absent; fresh roles with the same names reconstruct ownership. Restoring untrusted SQL as admin is prohibited.

On interruption/error, do not start applications or declare success. Inspect target with preflight. Transactional restore is intended to roll back object/data changes; verify emptiness before retrying. If not empty, retain the failed target for diagnosis and use another fresh project; the tool has no drop/clean switch. Do not manually bypass the nonempty guard.

After restore verify original user/password login with **new signing keys**, current memberships, cross-tenant 403, document READY/chunks/real retrieval scores and source identity, conversation creator-only history and failed/completed run records, manual proposal approval/execution history and task replay, audits, Flyway checksum validation, role safety and ordinary-role RLS behavior. A successful `pg_restore` exit alone is insufficient. Restore pilot traffic only after independent review. Retain the source until recovery acceptance authorizes cutover; this task performs no production cutover.

## Rehearsal, RPO and RTO

The [pilot drill](pilot-deployment-operations.md#local-synthetic-rehearsal) provisions synthetic users, tenants, knowledge, conversations and a real different-manager approval/OPEN task, exports authenticated ciphertext, stops the source, restores to an independent volume and compares every platform table's sorted row count/hash before API checks. It reports actual sizes/durations; no fabricated recovery guarantee or paid model call is involved. Exact measurements and negative test evidence belong in [EAAP-012](../tasks/EAAP-012.md).

There is no WAL/PITR or replication. RPO equals time since the last independently retained valid snapshot, and may be worse if backups fail unnoticed. No scheduler is installed. Proposed pilot operating target: daily backup, weekly restore rehearsal, operator review after each release; the architect/operator must approve capacity and frequency. RTO includes provisioning, obtaining images/model/config/keys, restore, validation and human cutover; local synthetic seconds are not a production SLA. Monitor backup age/failure through the operator process. Loss of the offline key makes its archives unrecoverable; loss of both source and off-host copy is unrecoverable.

## EAAP-013 framework compatibility rehearsal

Boot4.2.0 uses the unchanged Flyway V1–V8 schema and the same PostgreSQL17/pgvector0.8.7 archive format. No V9 or development database migration is needed. The updated acceptance drill adds actual identity-ingress network checks before taking the snapshot, then runs the existing encrypted export, negative archive tests, independent-cluster restore, exact table comparison, migration validation, RLS and restored business replay. See [EAAP-013 evidence](../tasks/EAAP-013.md). Measurements are synthetic local observations, not guaranteed recovery objectives. Preserve existing accepted archives; use fresh configs, image locks and empty restore clusters for rehearsal.

## EAAP-015 schema compatibility

Current startup requires V9. Archive validation accepts exactly reviewed V8/V9
manifests and reports their actual version. Restore checks that version with
applications stopped; a V8 archive must then be explicitly migrated to V9 before
current preflight/start. Never edit archived Flyway history or earlier SQL.
The independent rehearsal also verifies restored discovery returns only the
current user's tenant and runtime still cannot SELECT identity tables. It never
migrates development/customer data. See [EAAP-015](../tasks/EAAP-015.md).
Restoring old data can resurrect approved post-snapshot revocations/erasures;
reconcile under operator approval before reopening ingress. No deletion ledger
or automatic reconciliation is implemented.
