# ADR 0008 — Tenant invitations and access management

Status: implements the approved EAAP-009 specification and correction task; pending independent architectural/security review. EAAP-000 through EAAP-008 are accepted. This record describes implementation, not architect acceptance. See [delivery evidence](../../tasks/EAAP-009.md) and [operations](../../engineering/tenant-access-operations.md).

## Identity and application boundary

Keep the existing four services, global normalized-email user identity, Argon2id encoder, offline OWNER bootstrap, enabled-user checks, RS256 login and 900-second Bearer tokens. Spring's `access` module owns tenant access use cases; its API extracts the actor exclusively from the verified JWT subject. Infrastructure implements `AccessStore`; domain secret/password/role rules have no HTTP or persistence dependency. Python does not authorize invitations or modify identities. React uses the existing same-origin client and memory-only session. No dependency, service, provider, role cache or unrestricted registration route is added.

| Action | OWNER | ADMIN | MEMBER |
| --- | --- | --- | --- |
| Member directory | Tenant emails and roles | Tenant emails and roles | Own email; other emails null; UUID, role and joined date |
| Invite | ADMIN or MEMBER | MEMBER | Denied |
| Invitation metadata | Tenant invitations | Tenant invitations | Denied |
| Cancel PENDING invitation | Any in tenant | Own MEMBER invitations | Denied |
| Change role | Other ADMIN ↔ MEMBER | Denied | Denied |
| Revoke | Other ADMIN/MEMBER | Other MEMBER | Denied |

Every OWNER membership and self-mutation are protected. No OWNER assignment, demotion, revocation or transfer exists. Tenant UUID is a requested scope, never proof of membership. All operations authorize current enabled membership; mutations recheck the actor in the database transaction. A no-op role update returns success without changing creation timestamps or adding an audit event. Revocation deletes only the membership; global identity, other memberships, runs, proposals, tasks and decisions remain intact. Subsequent tenant requests read current membership with the same JWT. Already authorized in-flight operations retain the accepted EAAP-003 race limitation.

## Token and lifecycle

Generate 32 SecureRandom bytes, encode canonical unpadded base64url (43 characters), and store only SHA-256 of that representation. Canonical encoding is checked with a fixed-size constant-time comparison; PostgreSQL performs indexed digest equality, not a claimed constant-time database lookup. Creation returns `{invitation, token}` once with HTTP201/no-store. Metadata never includes digest or token. Only the creation view retains the raw token transiently; a deliberate Copy action puts it on the user's clipboard. Navigation, tenant switch, refresh, dismissal, logout and reload remove the component's copy. Clipboard copies and external delivery remain the operator's responsibility.

V7 adds `tenant_invitations` with globally unique digest, tenant/user foreign keys, normalized email constraints, ADMIN/MEMBER role constraint and strict lifecycle timestamp checks. Lifetime is exactly 48 hours from the database clock. States are PENDING, ACCEPTED and CANCELLED. Expiration is derived as `expired=true` for PENDING rows whose expiry has passed; no scheduler or EXPIRED transition exists. Expired rows do not count toward the five-live-pending quota or same-email live uniqueness rule. Listing is paginated, including terminal and effectively expired rows.

New acceptance requires only token, matching normalized email and a nonblank well-formed 16–128-character password (Java/HTML UTF-16 units). Existing Argon2id parameters are unchanged (v19, memory19456, iterations2, parallelism1). Hashing occurs outside database transactions. A valid invitation atomically creates one global identity, its membership, acceptance state and audit. An existing email receives generic410; its password is never overwritten. Acceptance issues no JWT; the recipient uses normal login.

Existing acceptance requires Bearer plus token only. The database locks/checks the enabled authenticated user and matching email, inserts just a membership, and consumes the invitation atomically. Existing membership returns409 without role changes. Missing/malformed/expired/cancelled/consumed tokens, wrong recipient and unavailable new-account acceptance return generic410. Weak/invalid input returns400; missing Bearer401; permission denial403; absent tenant-scoped targets404; duplicate invitation/terminal cancellation409; quota/admission limits429 with Retry-After60. Repeated cancellation and cancellation after acceptance return409. A request after an ambiguous network failure must be inspected, not retried automatically.

## V7 database authority and RLS

V1–V6 are unchanged. Both new tables are migration-owned with ENABLE/FORCE RLS. Runtime has no direct table privileges on either, no new global users/memberships DML or SELECT grants, no schema ownership, role membership, BYPASSRLS or DDL authority. The existing V2 login capability can return a credential hash to trusted authentication code; this is unchanged and must not be described as hash secrecy against compromised runtime credentials.

All eight exposed functions are migration-owned SECURITY DEFINER, have fixed empty `search_path`, static fully qualified SQL and revoked PUBLIC execution; only `eaap_runtime` receives EXECUTE:

| Function | Exact authority |
| --- | --- |
| `access_members(t,a,n,o)` | Current tenant directory; member email redaction; bounded page |
| `access_invitations(t,a,n,o)` | Manager-only tenant metadata page; no digest |
| `access_invitation(t,a,i)` | Manager-only exact tenant metadata after create/cancel; no separate HTTP discovery API |
| `access_invite(t,a,i,e,r,h,c)` | Current allowed inviter role, bounded issuance, atomic creation audit |
| `access_cancel(t,a,i,c)` | Current allowed cancellation, PENDING only, atomic audit |
| `access_member_change(t,a,u,r,remove,c)` | Current actor and target checks; protected OWNER/self; only permitted role change or membership removal plus audit |
| `access_accept_new(h,e,p,u,c)` | Valid capability and matching email only; validated existing Argon2 format; new identity and membership plus consumption/audit |
| `access_accept_existing(h,a,c)` | Valid capability and matching enabled actor only; new membership plus consumption/audit |

Private invoker helpers `access_actor`, `access_page`, `access_tenant_lock` and `access_redeem` have neither PUBLIC nor runtime EXECUTE. The SECURITY DEFINER caller supplies their migration authority. Invitation SELECT policy permits privileged digest lookup across tenants, necessary to derive a tenant from a capability; runtime has no table access. Invitation INSERT/UPDATE policies require the transaction-local tenant; UPDATE additionally requires previously PENDING. No DELETE policy exists. Audit INSERT/SELECT policies require that tenant; no UPDATE/DELETE policy exists. Audit is append-only through these functions, not tamper-proof against the privileged schema owner or administrator.

**Actor UUID arguments and `app.tenant_id` are assertions by trusted Spring code, not cryptographic database authentication.** PostgreSQL rechecks the supplied actor's actual current role but cannot prove who supplied its UUID. Compromised application code/runtime SQL credentials can impersonate a known authorized actor and set another context. This delivery does not claim protection against that compromise. Ordinary HTTP callers cannot select actor IDs: strict schemas reject added authority fields and the server uses only the verified subject. Existing identity/migration/operator credentials retain their separate trust levels.

## Concurrency, auditing and bounds

All access mutations acquire the same per-tenant transaction advisory lock before rechecking current authority/state. Membership and enabled-user locks stabilize actor checks; membership targets are locked for updates. Acceptance derives the tenant from the digest, locks that tenant and rereads state/expiry. A second redemption sees a terminal state; failure rolls back identity, membership, consumption and audit together. Expiry is checked again after membership insertion using one captured acceptance timestamp. No token consumption survives failed membership creation.

A pending invitation cannot restore access when a revocation audit event for that email/tenant occurred after issuance. Cancel the old invitation and explicitly issue a fresh one to reinvite. Redemption also verifies the inviter still has authority for the originally invited role: removed/disabled/demoted issuers cannot leave more powerful pending capabilities. These conservative lifecycle choices are recorded for independent review; they do not disable the recipient's identity.

`tenant_access_audit` stores five categories: INVITATION_CREATED, INVITATION_CANCELLED, INVITATION_ACCEPTED, MEMBERSHIP_ROLE_CHANGED, MEMBERSHIP_REVOKED. It records tenant, authenticated actor where applicable, target/invitation, previous/new role where applicable, trusted timestamp and request correlation UUID. Anonymous new acceptance has no authenticated actor. No token, digest, password, password hash, JWT or full HTTP body is in audit. Foreign keys reference global users/invitations rather than disposable memberships.

List size1–100, offset≤10000, one extra row for hasNext. Database functions independently bound fetch size≤101. Indexed digest lookup, tenant/date pages, live invitation predicates and tenant/target revocation history avoid N+1 and unrestricted identity listing. Tenant writes serialize and can return a sanitized persistence failure when lock/statement deadlines expire. JDBC query timeout3seconds and acceptance transaction timeout5seconds supplement existing pool/lock/socket limits. No external service or model call participates.

Acceptance has an independent instance of the existing in-memory guard: two concurrent slots, five attempts/peer/minute, twenty/process/minute, at most1024 tracked peers; immediate429, no queue. Both acceptance routes share this guard; login retains its own existing guard. Bodies are capped at4096bytes and parsed with strict fields/types/duplicate/trailing-token rejection. Hashes are bound directly without Spring parameter tracing; database exceptions are replaced before framework DEBUG diagnostics can expose private values. Existing PostgreSQL role logging restrictions remain required.

## Limitations and verification

This is a development foundation. Per-process throttling is not distributed and resets on restart; a reverse proxy makes browser requests share its peer address because forwarding headers are not trusted. TLS, production abuse controls, email delivery/verification, ownership transfer, password reset and account recovery are separate decisions. Possession of a delivered token permits new-account creation for its invited email; no independent mailbox verification is claimed. Browser script compromise, external clipboard history and privileged database compromise remain outside the guarantees. Audit/invitation retention is not scheduled and must be decided before production growth. No new caching or latency benchmark is claimed.

Real PostgreSQL tests cover fresh/repeated V7 migration, capability ACLs and fixed search paths, forced RLS and no runtime table/identity DML, missing/mismatched context, role abuse, disabled recipients and revoked issuers, expiry/cancellation/replay, password preservation, atomic rollback, concurrent quota/duplicate issuance/redemption/revocation/cancellation, context cleanup and preserved history. Frontend and isolated HTTP/Chromium evidence is recorded in the [task report](../../tasks/EAAP-009.md).
