# EAAP-012 — Operational security and disaster recovery

Status: implementation and local verification complete; pending independent architect/security review. No public deployment, paid inference, purchase, merge, force-push or EAAP-013 work is authorized or performed.

## Inspection and architecture

Started from accepted master `ccf0d42e239139d4f834e2719c35de02a3e4da53` (EAAP-011 PR14, independently verified merged), clean working tree, origin `https://github.com/HamzaElmansouri-Pr/Enterprise-AI-Agent-Platform.git`. Working branch: `feat/eaap-012-operational-readiness`. Reviewed root AGENTS, existing ADRs/engineering guidance, all service manifests/locks, Compose/Dockerfiles, Flyway V1–V8, identity/current memberships, knowledge worker/E5 artifact identity, bounded LangGraph runs/conversations, Tool Gateway approvals/replay, frontend token lifecycle, existing logs/health and GitHub Actions. No conflicting user changes existed.

Baseline: Java21/Boot3.5.16, Python3.12/FastAPI0.165.0/LangGraph1.2.24, React19.3.1/TypeScript6.0.3, PostgreSQL17/pgvector0.8.7. Existing security boundaries remain: modular Java control plane and Tool Gateway, Python inference/graph, React UI, one database. There are 17 business tables plus Flyway history; 15 tenant tables have forced RLS. Global users/memberships instead use narrow identity capabilities. GUC assertions still trust application code. No schema migration is needed or changed.

## Significant files and behavior

- `deploy/pilot/compose.yaml`, hosted overlay, Nginx config/web Dockerfile: independent opt-in four-service deployment; HTTPS same-origin ingress only; private dependency networks; nonroot/read-only/capability/resource restrictions; protected mounted files. Java/Python/pilot Node/Nginx/PostgreSQL bases are digest-pinned at existing approved versions.
- `deploy/scripts/pilot.py`: strict external configuration, local Unix Docker only, strong distinct file secrets, JWT/TLS pair/hostname/version checks, artifact integrity, immutable image/revision lock, isolated offline migration/bootstrap and private diagnostics. Real pilot builds require a clean configured commit; local tests may build a clearly experimental working tree.
- `deploy/scripts/backup.py`: actual bounded custom-format pg_dump; CMS authenticated AES-GCM/RSA-OAEP encryption; protected tmpfs plaintext; manifest/digest/version validation before any restore database access; exclusive publication and isolated transactional restore with ownership/grants/RLS preserved and fresh role passwords.
- `deploy/scripts/acceptance.py`, `failures.py`, `recovery_failures.py`: uniquely owned synthetic HTTPS business/recovery/failure drill; no development target selector; source/target independent clusters; protected generated credentials; removes owned containers/networks while retaining evidence/volumes for inspection.
- `deploy/tests/`: real OpenSSL positive/negative crypto, source-target guards, failure cleanup/no overwrite, protected files, actual Compose resolution and rejected unsafe mutations. `deploy/scripts/check.py`: source credential/archive/header/map gates.
- Spring `operations/`, pilot YAML and environment-processor registration: fail-closed pilot configuration, private management9090 readiness/liveness, authenticated fixed counters/revision/uptime. Existing request/execution/recovery/knowledge paths record bounded categories; transaction-bound terminal counters increment after commit.
- Python `operations.py` and existing service adapters: explicit file secrets/pilot checks, pinned artifact readiness, protected diagnostics/fixed counters, existing provider/graph behavior retained. Development remains compatible.
- PostgreSQL bootstrap: optional file credentials, preserving accepted environment workflow outside pilot; no runtime role elevation.
- Maven properties: focused Jackson2.21.7/Tomcat10.1.59/JDBC42.7.12/Log4j2 2.25.5 security fixes; Boot/Framework major versions unchanged. Existing locks and app APIs stay unchanged.
- CI: preserve Java/Python/React/evaluation gates; add fast Compose/crypto/startup/source gates. Heavy model/recovery tests remain documented local runs, with no paid credential requirement or sensitive artifact upload.
- [ADR0011](../architecture/decisions/0011-operational-security-and-recovery.md), [pilot operations](../engineering/pilot-deployment-operations.md), [recovery operations](../engineering/backup-and-disaster-recovery.md), [threat model](../security/operational-threat-model.md), [privacy proposal](../security/data-retention-and-privacy.md), root and documentation indexes.

## Security and performance assessment

Ingress isolates management/runtime/database ports. Secrets stay outside source/build contexts and are not Compose environment values; transient privileged bootstrap/client environments and Docker host administration remain trusted. Internal HTTP/shared secrets are not mTLS/workload identity. No raw prompts, documents, passwords, JWTs, invitation tokens or provider keys are intended in telemetry. Retention is proposed policy requiring human approval, not automatic erasure/compliance. Backup encryption authenticates bytes, not sender provenance; key custody/off-host copies are required.

Memory caps total approximately 2.875GiB across pilot services, excluding host/build overhead; CPU caps total3.5. Runtime sampling during a concurrent synthetic drill observed about3.5MiB web,293MiB Java,846MiB Python (loaded local E5),33MiB PostgreSQL. This is one local observation, not a capacity benchmark or peak guarantee. Probes use bounded database/connect deadlines and no paid calls; readiness can fail without liveness failing. Fixed counters avoid unbounded labels. Backup work is streamed except the explicitly bounded manifest/TOC, with a1GiB archive ceiling and tmpfs headroom required. No cache, N+1 redesign or new persistence query is introduced in request business flows.

Advisory review: all91 Maven runtime coordinates,61 production Python packages and npm lock checked. Focused Java fixes remove the reported Jackson/Tomcat/JDBC/Log4j alerts. Two Spring MVC package alerts remain on6.2.19; the required SSE-view/XSLT-view capabilities are absent. See the threat model for primary-source conditions, severity differences and the outstanding OSS-major-versus-commercial support decision. No claim of a clean OS-image scan or production certification is made.

## Validation commands and results

Commands assume documented host prerequisites; this workstation used the prepared JDK21/Maven/uv/Node22 caches outside the repository and local Docker access. No developer database was migrated, reset or read for the drill.

```sh
cd services/control-plane
./mvnw -B -ntp verify
# root
python3 -m unittest discover -s deploy/tests -v
python3 -m deploy.scripts.check
python3 -m evaluation validate --dataset evaluation/datasets/core-v1
python3 -m unittest discover -s evaluation/tests -v
# services/agent-runtime
uv run --locked ruff check .
uv run --locked ruff format --check .
uv run --locked pytest
uv run --locked ruff check --config ../../deploy/ruff.toml ../../deploy
uv run --locked ruff format --check --config ../../deploy/ruff.toml ../../deploy
# root
npm --prefix apps/web run lint
npm --prefix apps/web run typecheck
npm --prefix apps/web test
npm --prefix apps/web run build
npm --prefix apps/web audit --json
python3 -m evaluation run --dataset evaluation/datasets/core-v1 --suite all --mode offline --model-dir /absolute/external/e5-small --output /tmp/NEW-eaap012-evaluation
```

Completed for the final code: Java270 passed (91 unit,179 integration), Python118 passed, React88 passed with lint/types/build, evaluation48 unit tests and dataset validation passed, deployment18 tests passed, Ruff/source gates passed, npm0 known vulnerabilities and Python audit0 known vulnerabilities. A complete72-case offline evaluation passed with24 invariant checks and zero paid calls; the security-patched repeat also completed72/72 with24 invariant checks,16 synthetic SDK calls and zero paid calls. Documentation references, shell syntax, migration immutability, source maps, secret patterns and final diff are reviewed before commit.

## Disaster recovery evidence

A complete real rehearsal passed: HTTPS login/tenant access, actual PENDING→READY E5 ingestion/retrieval, durable failed provider-unavailable conversation plus replay, creator-approval403, different ADMIN approval with no task until execution, one OPEN task and replay. Then actual encrypted export, independent fresh-role/volume restore, every platform table's sorted row count/hash equal, Flyway validation/no-op, new JWT keys invalidate old tokens, original account password/current memberships work, restored knowledge/history/approval/task records and ordinary-role RLS work.

The earlier successful drill exported154359 bytes in0.256s and restored in1.557s; end-to-end258.39s. These are small synthetic local measurements, not RPO/RTO guarantees. It tested absent DB at startup, missing secret/invalid JWT failure, wrong migration credential, runtime outage with core identity available, DB interruption with readinessDOWN/livenessUP, controlled process restart, missing restore role, source-cluster rejection and interrupted archive transaction rollback. Crypto unit tests reject missing/wrong credentials, corruption/truncation/CBC, incompatible versions/digest, unsafe permissions, unwritable output, failed export/encryption and interrupted output without overwriting valid archives. The final repeat completed successfully with the security-patched, digest-pinned images and all final counters. Its [machine-readable report](../../deploy/reports/pilot-recovery.json) records 17 passing checks, 154359 encrypted bytes, 0.392s export/encryption, 1.606s restore and 365.20s end-to-end. It also verified privacy-safe logs, observed Docker unhealthy status during the deliberate DB outage, and replayed the restored proposal to the original task ID. A separate fresh-cluster test verified rejection of nonempty public/extension/custom schemas. The final [offline evaluation summary](../../deploy/reports/offline-evaluation.json) and [Maven advisory review](../../deploy/reports/maven-advisory-review.json) retain actual results. Test builds are explicitly experimental working-tree builds on the accepted parent, not clean production-release artifacts.

Rehearsal fixes: Nginx temporary paths were moved to tmpfs after the first read-only startup failed. PostgreSQL health now checks TCP to exclude the entrypoint temporary Unix-socket bootstrap server. A later run exposed exhausted Docker address pools from retained stopped test networks; cleanup now removes only owned containers/networks while preserving volumes. One restored Flyway check failed before cleanup improvements and passed on retry; final independent-run validation passes without a retry. No checksum or privilege check was bypassed.

## Delivery and outstanding review

The required commit is `feat: harden pilot deployment and implement verified disaster recovery`, pushed only to the task branch with a PR targeting master. Actual SHA/PR/CI evidence is reported with delivery; nothing is merged automatically.

Review gates for real pilot: CA/DNS/firewall/renewal, host/swap/log protection and OS-image scanning, independent off-host encrypted storage/schedule/key escrow, approved RPO/RTO and retention/incident ownership, Spring support path, future multi-host internal TLS/workload identity, and the inherited shared proxy login budget (5 attempts/minute). No unapproved purchases/framework-major migration, cloud deployment, PITR/HA, auto-deletion or paid model validation was performed. Documentation file references, source/secret gates, shell syntax and final diff checks pass. All accepted V1–V8 migrations remain unchanged. Recommended next step is architect/security acceptance and an operator-led synthetic recovery rehearsal before authorizing any real pilot exposure.
