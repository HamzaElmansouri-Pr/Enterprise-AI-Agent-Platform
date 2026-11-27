# ADR 0014 — Guided onboarding and authenticated tenant discovery

Status: implementation within the explicitly approved EAAP-015 scope, pending
independent architecture, product and security review. EAAP-000–014 are accepted.
No public registration, new role, provider, framework or destructive lifecycle
operation is authorized.

## Trust model and API

`GET /api/v1/me/tenants?page=0&size=20` returns only the current enabled caller's
membership organizations as `{items:[{tenantId,name,role}],page,size,hasNext}`.
The actor is the canonical immutable subject of the existing validated RS256 JWT.
The route admits no body authority, user/tenant/role parameter, filter or alternate
identity. Unknown or duplicate query parameters fail 400. Missing/invalid/expired
JWT or disabled account is 401. An enabled user without memberships receives an
empty page. No names, emails, owners, members or counts from other organizations
are returned. Every successful response is no-store; roles are current database
values rather than token claims or browser state.

The identity module owns the `TenantDirectory` port and `TenantDiscovery` use
case. Its prepared JDBC adapter executes one bounded capability call, with a
three-second query deadline. Pagination reuses the project's page/size/hasNext
shape: size 1–100, default 20, offset ≤ 10000, overflow-safe multiplication, size+1
lookahead and deterministic tenant UUID ordering. There is no COUNT query or
per-tenant request. Membership primary key `(user_id,tenant_id)` and tenant/user
primary keys support the access path; no speculative index or cache is added.

The authenticated filter checks enabled state; the application and capability
also enforce current enabled identity. Membership reads are fresh per request.
Concurrent revocation can occur after a read snapshot; a displayed name is not
an authorization grant. Opening a workspace still calls the unchanged tenant
context endpoint. Every later business request retains its existing live
membership, role and RLS checks. No auto-selection occurs even for one result.

## V9 privilege design

V1–V8 remain byte-for-byte immutable. V9 adds exactly one migration-owned
SECURITY DEFINER function `platform.discover_tenants(actor,n,o)` and a SELECT-only
tenant policy applicable solely to `eaap_migration`. It adds no table, identity
SELECT grant, role membership, superuser/BYPASSRLS flag or generic directory.
PUBLIC execution is revoked; only runtime receives EXECUTE. SQL is static,
fully qualified, with empty search_path. Input limits are independently checked
in PostgreSQL (n 1–101, offset 0–10000).

The policy requires a real membership joined to an enabled user whose UUID
matches a function-established transaction-local `app.discovery_user_id`.
The function saves the previous assertion, binds the server-provided actor,
returns the bounded joined rows, and restores the assertion before returning.
Statement failure rolls back its local changes. No persistent tenant/user
connection setting is introduced. An absent previous assertion normalizes to
empty after use. Pooled tests verify unchanged tenant context, restored actor
assertion and no direct tenant rows without ordinary authorized context.

FORCE RLS remains enabled. Runtime cannot use the definer-only policy through
direct SELECT even if it sets that custom setting. Runtime still cannot SELECT
global users/memberships or assume migration. The migration owner remains a
privileged schema operator capable of changing policy, as before.

Actor arguments and PostgreSQL custom settings remain assertions by trusted
Spring code, not database cryptographic user authentication. Compromised runtime
SQL credentials could call this identity function for another known UUID, as
with existing identity capabilities; this is not claimed to resist complete
application/SQL compromise. HTTP users cannot choose that UUID. Tenant discovery
does not broaden the public API into a user-search facility.

PostgreSQL behavior references: [CREATE FUNCTION](https://www.postgresql.org/docs/17/sql-createfunction.html)
and [CREATE POLICY](https://www.postgresql.org/docs/17/sql-createpolicy.html).

## React guidance and lifecycle

The picker displays authorized names/current roles, paginates, offers explicit
refresh and an optional support-only manual UUID fallback. Account expiry/logout
disposes in-memory token scopes. Switching organization aborts prior requests,
unmounts feature state, resets paid-call acknowledgement and fetches a fresh
directory. Late responses cannot populate a newer scope. Names are ordinary
escaped React text, never markup or URL destinations. JWTs remain memory-only.

The selected name is display metadata; membership/role is verified by the context
endpoint. A concurrent name change may remain visible until re-selection because
there is no rename API/subscription. The manual fallback uses a neutral selected
organization label and secondary technical ID, never a fabricated name.

Overview contains a contextual guide using existing bounded agent/document/
proposal pages and one manager-only invitation page. It marks only facts observed
in those pages: usable DRAFT configurations, uploaded documents, READY/nonzero
chunks associated with a visible DRAFT agent, and invitation records. Missing
first-page evidence is labeled "not observed on these pages", not global absence.
No durable onboarding table, hidden completion flag, paid call, automatic upload,
proposal approval or business action is added. Search completion and hosted
availability are explicitly untracked/unverified; no provider-status API is
invented. Guide buttons navigate to real existing operations only.

Team refreshes read-only records on return/focus, at most once per five seconds,
and retains manual refresh. Refresh pauses while an issued token, confirmation or
write is open. Upload acceptance copy defers to the authoritative processing row
instead of retaining a stale PENDING claim. Shared table regions provide visible
sideways-scroll instructions, keyboard focus and focus outline. Navigation
focuses the main content. Source citations explain how to inspect filenames/pages
without inventing source-download permissions.

## Deployment, compatibility and remaining limits

Apply V9 offline in a reviewed maintenance window. It creates function/policy DDL,
which can briefly lock metadata; it rewrites no business rows. Existing statement/
lock deadlines apply. Failure rolls back DDL; deployed defects need a new forward
migration. No down migration, Flyway repair or development-volume modification.

Current pilot startup requires schema 9. Encrypted archive validation accepts only
reviewed schemas 8/9 and preserves the actual manifest version. Restore validates
the restored version while services are stopped. For a V8 archive, restore first,
run the explicit migration to 9, then run current preflight/start. Old binaries
have their original schema preflight and must not be assumed V9-compatible.
Independent restore, runtime isolation and V8→V9 preservation tests are required.

Human usability and hosted model intelligence remain unmeasured. Privacy/deletion,
legal holds, provider data terms, retention periods and real-pilot release policy
remain human approval decisions. EAAP-015 delivers plans for these, not deletion
endpoints, legal compliance or a public launch.
