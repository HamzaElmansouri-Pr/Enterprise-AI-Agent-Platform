# Privacy and data lifecycle implementation plan

Status: implementation-ready engineering proposal, **not an enacted retention or
erasure policy**. EAAP-015 performs no deletion, offboarding, customer import or
audit erasure. Existing [retention proposals](data-retention-and-privacy.md) remain
unapproved assumptions. No GDPR or Moroccan privacy-law compliance claim is made;
jurisdiction, lawful basis, roles and legal obligations require separate counsel.

## Ownership, storage and dependencies

"Tenant owner" below means the designated human business data owner, not automatic
authorization for an OWNER account to erase data. Global identity belongs to the
platform identity operator plus the individual; a tenant does not own another
tenant's use of the same account. Ordinary application access is authenticated,
current-member and tenant-scoped; private runs/conversations additionally require
the creator. Host/DB/recovery administrators are separately privileged.

Every category below requires an approved purpose, retention/hold decision and
named operator authorization before a destructive implementation or operation.
None currently has an approved automatic expiry/deletion job. Live records enter
encrypted full-database snapshots unless explicitly stated otherwise; a future
live erasure does not erase historical backups. "Provider" identifies possible
sharing only when hosted execution is separately enabled; offline testing shares
none of these records with a hosted model.

| Category / data owner | Existing storage and access | Deletion dependencies / referential integrity | Audit preservation | Provider exposure |
| --- | --- | --- | --- | --- |
| User account / individual + identity operator | platform.users: normalized email, Argon2 hash, enabled state; narrow identity functions, no runtime table SELECT | Memberships, invitation actors/recipients, audit actors, agent runs, conversation creators, proposal/decision/task actors reference user. A multi-tenant identity must not be deleted for one tenant's request. Disable is not erase. | Preserve approved actor provenance; any pseudonymization needs legal and FK/digest design approval. | Email/hash/enabled state are not sent by the answer adapter. |
| Tenant registry / designated organization owner | platform.tenants: name/UUID; forced RLS; discovery reveals only own membership names | All tenant-owned configurations/content/history reference tenant. No tenant delete route; offboarding is a separate orchestrated proposal. | Preserve scope identity for retained decisions/audit. | Registry row is not directly sent; tenant-authored instructions may identify organization. |
| Membership / identity operator + organization owner | platform.tenant_memberships: user/tenant/role; fixed capabilities | Existing revoke deletes only membership, preserves global user/history. In-flight snapshot race is documented. Other tenant relationships remain intact. | tenant_access_audit records change/revocation; deleting membership must not erase it. | Not sent. |
| Invitations/token history / organization owner | tenant_invitations: normalized recipient, role, digest, status/expiry/actors; manager-only fixed capabilities. Raw token only returned once in transient browser/clipboard | Audit rows reference invitation; recipient/issuer references global user. Token expiry is derived, not deletion. Cancel/revoke dependencies prevent replay; purge must preserve anti-reinstatement evidence. | Preserve issuance/acceptance/cancellation provenance and post-issuance revocation checks. | Not sent. External secure token handoff is outside application delivery. |
| Agent configuration / organization owner | agent_configurations: purpose/instructions/status; tenant readers and managers | Runs, associations, conversations, proposals reference agent with tenant composite FKs. Archive is terminal state, not erase. | Retain configuration identity needed to explain historical decisions; future content redaction may affect evidence. | Instructions are sent on authorized hosted calls; not all configurations are exported. |
| Uploaded documents / organization owner | knowledge_documents metadata; knowledge_content immutable BYTEA source; tenant-authorized metadata, no source-byte download API | Chunks and associations reference document. Content/chunks cannot be physically deleted by runtime today. Filenames/checksums/status may remain in references/backups. | An erasure approval must account for copied evidence/citation references in runs. | Original bytes are not sent by the answer adapter; selected extracted passages can be. |
| Extracted text / organization owner | knowledge_chunks.text with document/tenant/model/ordinal; tenant retrieval plus trusted leased worker | Delete atomically with embeddings/chunks and source linkage in a reviewed purge; avoid READY metadata with missing chunks. Active processing leases must be quiesced first. | Preserve approved tombstone/provenance without retaining erased text by accident. | Only bounded authorized retrieved passages are sent. |
| Embeddings / organization owner | knowledge_chunks.embedding vector(384), fixed local E5 identity; same RLS scope | Derived from chunk text; remove consistently with text/source. Not anonymous, not safely retained independently by assumption. | Model/manifest identity may be retained only under approved evidence policy. | Vector coordinates are not sent by the hosted adapter. Local CPU embedding is separate. |
| Agent runs / creator + organization data owner | agent_runs: question, answer, citations, usage/failure metadata; creator-only application reads | conversation_turns bind source run and creator; run deletions must address referencing turns. Citations are durable JSON metadata, not a guarantee source bytes still exist. | Retain only approved support/accountability fields after reviewed redaction; terminal updates currently immutable. | Bounded question/context/history and instructions can leave the local boundary; provider output returns here. |
| Daily admission / organization owner + platform operator | agent_run_daily_quota: tenant/date/count; atomic admission | Needed for retry/budget abuse protection. Deleting current-day counters can reset allowance; never use retention as a quota bypass. | Retain minimal approved operational evidence; no prompt content. | Not sent. |
| Conversations/messages / creator + organization owner | conversations metadata, conversation_turns ordering/request IDs linked to agent_runs; tenant+creator forced RLS | Turn FKs bind conversation and run; projected message text lives in runs. Preserve durable request identities during any future privacy redaction to avoid paid replay. Close/archive is not erase. | Decide retention for request/run tombstones independently of private message content. | Up to six successful prior pairs are sent for a newly authorized turn. No whole-conversation export. |
| Tool proposals / organization owner | tool_proposals: immutable exact arguments/rationale/digest, creator/execution state; managers/all, MEMBER/own | Approval and task composite FKs bind exact proposal/digest/creator. Mutating payload would invalidate reviewed approval; runtime cannot erase immutable content. | Preserve reviewed payload/digest link while legally required; any redaction needs explicit schema/invariant design, not UPDATE bypass. | Manual proposal content is not sent; generated proposal intent/instructions go to the separately authorized model path. |
| Approval decisions / organization owner + accountable reviewer | tool_approval_decisions: reviewer/digest/decision/reason/time/correlation; proposal-scoped read | Tied to immutable proposal. Separation-of-duty CHECK/FKs and dispatch validation must survive future erasure. | Accountability evidence cannot be silently removed. Legal conflict requires a case-specific hold/redaction decision. | Decisions are not sent by answer/proposal adapters. |
| Internal tasks / organization owner | admissions_followup_tasks: title/description/priority/OPEN state, creator/source proposal; authorized tenant read | Unique tenant/source proposal prevents duplicates; deleting a task can break result FK/replay invariants. No editing/purge lifecycle currently exists. | Preserve exactly-one effect provenance; use future approved tombstone, not removal of replay key. | No external delivery; task rows are not sent. |
| Access audit / organization owner + security operator | tenant_access_audit actor/target/invitation/action/role/time/correlation; append-only capability paths, no runtime table reads | User/invitation FKs; revocation history participates in token redemption safety. Erasure must not make old invitations usable. | Preserve under approved audit/legal-hold rules. Do not erase evidence to fulfill a broad unreviewed tenant request. | Not sent. |
| Operational logs / security operator | bounded service correlation/status/timing/category logs in Docker/host logging, not a new database store | Identify every host/export/incident copy; application row deletion does not touch them. Do not introduce request-body/SQL bind logging. | Incident hold/custody and authorized export/redaction decisions remain operator-owned. | Logs are not sent to the model or an added telemetry vendor. |
| Encrypted backups / recovery operator + offline key custodian | authenticated CMS AES256GCM/RSAOAEP database archive, protected manifest/hash metadata | Contains all database categories and privileges; snapshots are immutable. Track copies/recipient keys/expiry and independent restore capability. Cannot selectively erase bytes inside a verified archive without creating a new reviewed archive. | Keep provenance/custody; loss of keys is loss of recoverability, not an approved erasure shortcut. | No provider sharing; off-host backup storage contract remains an operator decision. |
| Evaluation/research artifacts / evaluator + participant/research owner | versioned synthetic fixtures/reports; future human notes or authorized hosted outputs in restricted external storage | Do not mix real customer exports into Git fixtures. Future consent withdrawal/data requests cover authorized study artifacts and copies separately. | Keep minimal approved experiment provenance; scores cannot justify indefinite raw private text. | Only later explicitly authorized synthetic case context goes to hosted provider. Human notes never go automatically. |

## Proposed future workflow and API boundaries (not implemented)

Implement request/review/preview before execution. A future authenticated
`POST /api/v1/tenants/{tenantId}/privacy/requests` could submit a tightly typed
request for access review/redaction/erasure assessment. It must use JWT identity,
current permissions, explicit subject scope, purpose and client request UUID;
no arbitrary table names, predicates, SQL, filesystem paths or URLs. This route
does not exist in EAAP-015 and no frontend should call it yet.

A proposed read-only preview route would return bounded category counts,
blocking references, current holds, affected creators/other tenants as opaque
review-required signals (not another tenant's data), and archive-copy obligations.
Store only minimal request metadata. Do not put document/message bodies into
approval reasons. A separate privileged, offline operator execution capability
would require an approved plan digest and distinct human approval; ordinary
runtime receives no generic DELETE/UPDATE privilege. Exact roles, data-subject
verification and legal authority need architect/counsel approval first.

Forward migration design: create minimal request/decision/hold metadata with
tenant ownership and forced RLS, immutable decisions and idempotency; add reviewed
tombstone/redaction fields only where approved. Preserve existing primary/composite
FKs, proposal digest constraints and request/task uniqueness. Never change applied
V1–V9. An independent migration must define permitted transitions, legal hold
precedence, failure/recovery and role grants. No speculative V10 is created here.

Execution ordering depends on the approved category:

1. Freeze the exact scope/plan digest, verify identity/authority/holds, quiesce
   writes and processing leases for that scope, and take an approved protected
   backup according to the still-to-be-approved retention decision.
2. Inventory references across creators and tenants. Global account deletion stops
   if another tenant or retained actor FK requires the identity. Revocation alone
   can stop access while a privacy decision is reviewed; it is not erasure.
3. For approved source removal, plan associations → chunk text/vectors and source
   BYTEA → metadata/tombstone, in a transaction retaining consistent readiness and
   citation behavior. Account for prompt/evidence copies in durable runs and old
   backups. Do not silently cascade through approval or conversation history.
4. For private conversation content, plan turns/run redaction together; retain
   approved request outcome tombstones so a lost-response retry cannot dispatch
   again. A run referenced by another retained record cannot simply be deleted.
5. Proposal/task redaction needs a separate invariant-preserving design: reviewed
   payload digest, actor provenance, decision, effect uniqueness and terminal
   replay must remain auditable. If policy conflicts with immutable evidence,
   stop for legal/architect determination rather than disable constraints.
6. Commit minimal completion/audit metadata without erased private payloads.
   Fail atomically where possible; bounded resumable batches and a reconciled
   terminal state are required before relaxing the write freeze. No automatic
   destructive retries after ambiguous acknowledgements.

## Backup retention, restore and provider obligations

Every inventory row requires a documented schedule for all live/replica/export/
backup copies. The previous 7 daily + 4 weekly proposal is not enacted here. A future
approved erasure/revocation ledger must survive separately from the snapshot so a
restore cannot resurrect access or deleted content unnoticed. Restore only behind
closed ingress, reconcile approved post-snapshot actions/holds, verify references,
then independently authorize reopening. This task implements neither ledger nor
deletion reconciliation; runbooks must state that limitation.

Before any real data reaches a hosted provider, approve controller/processor
responsibilities, region/cross-border transfer, contract/data-use and retention
terms, subprocessors, incident notification and data-request assistance. The
bounded provider adapter and store=false do not establish zero retention or legal
compliance. Provider-side removal may require an external process; it cannot be
proved by deleting a local run. No such external operation is performed here.

## Approval questions and testable follow-on acceptance

The architect/data owner/counsel must decide purposes and jurisdiction, subject
verification, retention per category, legal/incident holds, audit redaction versus
preservation, operator separation, permitted exemptions, backup expiry/key custody,
provider terms, response deadlines and evidence retention. No default numeric
period is silently selected.

A later authorized implementation must test: wrong actor/tenant 403; creator-only
scope; OWNER cannot erase another tenant's global account; holds block execution;
preview matches actual FK graph; token revocation replay remains blocked; vector/
source removal excludes search; request IDs cannot replay paid effects; proposal
and task idempotency survives redaction; concurrent writes/leases are fenced;
partial/ambiguous failures recover without extra deletion; audit contains no
payloads; old-backup restore is reconciled before ingress. Use disposable synthetic
databases and an independent encrypted recovery rehearsal. None of these proposed
destructive features is claimed implemented or tested by EAAP-015.
