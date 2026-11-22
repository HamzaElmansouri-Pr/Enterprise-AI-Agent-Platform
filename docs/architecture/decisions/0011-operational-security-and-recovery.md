# ADR 0011 — Pilot security and logical disaster recovery

Status: implementation decision within EAAP-012's explicitly authorized deployment scope; pending architect review. This does not authorize production launch.

## Baseline and decision

EAAP-011 master `ccf0d42e239139d4f834e2719c35de02a3e4da53` provides Java 21/Spring Boot 3.5.16, Python 3.12/FastAPI 0.165.0/LangGraph 1.2.24, React 19.3.1/TypeScript 6.0.3, PostgreSQL 17/pgvector 0.8.7, Flyway V1–V8, four services and deterministic evaluation. It has developer Compose overlays and safe request correlation, but no authenticated encrypted backup/restore, private management readiness or dedicated TLS pilot profile.

Retain all four services and business boundaries. Replace Vite preview with Nginx in the pilot web image only. Terminate HTTPS there and route `/api/v1/` to the control plane on the same origin. Pin the Nginx and PostgreSQL images by digest; lock locally built application images by immutable image ID and OCI revision. Existing development images and overlays retain their accepted behavior. This is a single-host Compose pilot, without Kubernetes, Redis, a broker, a new database or a security SaaS.

Only web ingress is published. Separate private API, runtime and data networks constrain reachability. All processes run without root, with read-only root filesystems, bounded tmpfs, memory/CPU limits, dropped capabilities and no privilege escalation. A privileged Docker operator/host remains trusted. Compose file secrets are protected bind-mounted files, not an encrypted secret vault.

Pilot configuration is explicit and separate from `.env`. Startup rejects missing/weak/reused service secrets, invalid keys and unsafe supported settings. Operator profiles receive only the relevant migration/identity password. File contents are absent from Compose environment configuration. PostgreSQL bootstrap/clients necessarily use credentials transiently in their child environment; host/container administrators remain able to inspect them. Rotation is coordinated maintenance, not live multi-key rollover.

Private Spring management port 9090 separates liveness from bounded database readiness. Authenticated process diagnostics provide fixed counter categories, revision and uptime. Runtime readiness checks pinned artifacts/configuration, without paid inference. Core identity readiness does not depend on optional inference. Request logs retain safe correlation/status/duration; no access body logs or arbitrary metric labels are added.

## Recovery design

Export one consistent PostgreSQL custom-format database archive with `pg_dump`, including all platform records, vector data, indexes, policies, ACLs and ownership. Do not export global role passwords. Recreate the three nonsuperuser roles with fresh credentials using accepted bootstrap, install exact pgvector, then restore objects/data/privileges in one transaction. Verify Flyway V8 and forced RLS. No accepted migration is edited.

Use installed OpenSSL 3 CMS AuthEnvelopedData, AES-256-GCM, RSA-OAEP/SHA-256. Recipient certificate can be distributed separately from the protected offline private key. Never reuse JWT/TLS keys for backups. Plaintext exists only in a mode-0700 Linux tmpfs workspace and is removed on normal/error exit; SIGKILL/host crash may require operator cleanup. Authenticate the complete ciphertext and verify manifest/dump digest before contacting the restore database. A wrong key, damaged archive or incompatible manifest cannot trigger restore SQL.

Restore requires an explicit independent pilot cluster/project, exact versions, correct roles, empty application database (including public/custom schemas) and only PostgreSQL running. No clean/drop/reset option exists. Publish backup files atomically without overwrite. The manifest and SHA-256 detect archive damage; AEAD authenticates encrypted bytes. This does **not** authenticate the sender: anyone with the public recipient certificate can encrypt content. Trusted backup storage, provenance and authorized operator selection remain mandatory; only trusted database archives may be restored as an administrator.

## Consequences and limits

No physical/WAL replication or point-in-time recovery; no HA, cross-region guarantee, compliance certification, online key rollover, automated deletion or autonomous deployment. Logical export requires sufficient disk/tmpfs and a DDL freeze; large database performance is unproven. A 1 GiB archive ceiling intentionally bounds this small-pilot tooling. Recovery depends on independent key custody, image/model availability, protected off-host copies and rehearsed operator access. Internal traffic remains plaintext on private host networks with shared-secret authentication, not mTLS/workload identity. GUC-based RLS assumes trusted application code; arbitrary SQL with tenant assertions is outside that boundary.

See [pilot operations](../../engineering/pilot-deployment-operations.md), [recovery operations](../../engineering/backup-and-disaster-recovery.md), [threat model](../../security/operational-threat-model.md) and [privacy](../../security/data-retention-and-privacy.md).

Primary references: [PostgreSQL pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html), [pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html), [OpenSSL CMS](https://docs.openssl.org/3.0/man1/openssl-cms/), [Nginx TLS](https://nginx.org/en/docs/http/ngx_http_ssl_module.html).

The operational security review identified specific existing dependency advisories. EAAP-012 applies limited managed security overrides: Jackson BOM 2.21.7, Tomcat 10.1.59, JDBC 42.7.12 and Log4j2 2.25.5, with regression verification. Spring Boot/Framework major versions remain unchanged. Two currently unused Spring MVC view-rendering vulnerabilities require an explicit future support-path decision, documented in the threat model; they are not represented as fixed.
