# Data retention and privacy — proposed pilot policy

Status: engineering inventory and proposed policy, requiring architect/operator and jurisdiction-specific review. No GDPR, Moroccan-law or other legal compliance claim is made. No automatic deletion, export or retention scheduler is implemented in EAAP-012.

| Data category | Stored content / access | Proposed retention assumption | Deletion/recovery implication |
| --- | --- | --- | --- |
| Identity | Email, Argon2 password hash, enabled state; narrow identity capabilities and authorized operators | Account lifetime plus approved incident hold | Disabling is not erasure; no generic hard-delete workflow exists |
| Memberships | User/tenant IDs, roles and state; current membership checks | Tenant relationship lifetime, then approved audit window | Role revocation blocks subsequent authorization; historical records remain |
| Invitations | Bound email/role, token digest, expiry/use/revoke state; invitation capabilities | Expired/used records 30 days as a proposal | Plain token returned only at creation; expiry is not physical deletion |
| Agent configuration | Name, description, system instructions; authorized tenant managers | Active tenant lifetime, archived review after 90 days | Archive is a state transition, not erasure |
| Knowledge uploads | Original bytes, filename/hash/type/status, links; tenant-authorized API/operator | While required by tenant, quarterly review | Archiving/unlinking must not be claimed to remove physical bytes |
| Chunks/vectors | Extracted text, embedding, ordinal/model; tenant-scoped retrieval | Same retention as source document | Embeddings are potentially sensitive derived data; not anonymous |
| Conversations/messages | Titles, user and assistant text, creator/tenant/run IDs and statuses | Pilot proposal: 90 days unless approved hold | Creator access restriction is not encryption or automatic erasure |
| Runs/citations/usage | Inputs, results, evidence IDs, safe errors, duration, provider/model/token metadata | 90 days for debugging/evaluation proposal | Source text and prompts may remain in durable run records/backups |
| Tool proposals/decisions | Arguments, rationale, creator, approver, digest, timestamps/status | 1 year proposed for action accountability, subject to review | Immutable evidence and erasure obligations need a reviewed workflow |
| Followup tasks | Admissions task description, priority/state and provenance | Business purpose plus operator-approved closure window | OPEN tasks survive backup/restore; no purge process added |
| Audits/security events | Tenant/actor/action/object IDs, outcomes/timestamps | 1 year proposed, access restricted to incident owner | Audit retention must balance accountability and privacy |
| Operational logs | Safe correlation/status/duration/category; no body/token logging | 7 days local, 30 days reviewed incident logs proposed | Docker host retention/rotation must be configured by operator |
| Evaluation artifacts | Synthetic corpus/results only by default; explicitly reviewed hosted responses if authorized | Versioned synthetic baselines, human review for other content | Do not put private tenant exports into benchmark fixtures |
| Encrypted backups | Full database snapshot, including every category above | 7 daily + 4 weekly copies proposed; independent encrypted storage | Deleting live records does not erase older backups; offline recipient key custody required |
| Keys/secrets | Signing/TLS/internal/DB/provider and offline recovery key | Shortest operationally necessary lifetime; incident rotation | Preserve backup keys until all archives they protect expire; never log or version them |

## Boundaries and operator duties

Least-privilege tenant/creator access controls apply online. Database administrators and recovery operators can access plaintext business data. File secrets are protected host files, not encrypted managed storage. Encrypt and protect host volumes/swap/off-host media under approved operator policy. Never send raw rows, prompts, document content, invitation tokens, credentials or full API responses to logs, tickets or CI artifacts.

A hosted model, when explicitly enabled, receives the bounded authorized execution context through the accepted provider abstraction. Private Docker networks do not change provider-side processing/retention obligations. Provider contract, region, data-use controls and incident notification requirements need explicit review before real customer data is sent. Default rehearsal makes no paid provider calls.

The proposed durations above are planning assumptions, not enacted policy. The human data owner must approve purpose, jurisdiction, retention, lawful access, legal holds and deletion exceptions. Before implementing erasure, design referential-integrity/audit constraints, vector/chunk/source removal, provider obligations and an inventory of retained backup copies. No direct SQL deletion runbook is authorized here.

For a future validated erasure request, record the decision and backup-expiry schedule without including private payloads. Recovery from an older snapshot can resurrect previously deleted data; the incident owner must reconcile approved post-snapshot revocations/deletions before reopening ingress. This milestone does not build a deletion ledger or claim automatic reconciliation.

Incident response follows [pilot operations](../engineering/pilot-deployment-operations.md#diagnosis-and-incident-handling). Recovery access and evidence handling follow [backup operations](../engineering/backup-and-disaster-recovery.md). Separate duties where feasible between ordinary tenant managers, host administrators, backup storage operators and offline key custodians. Record access approvals and rehearse with synthetic data.
