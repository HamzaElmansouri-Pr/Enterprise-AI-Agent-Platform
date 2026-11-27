# Organization onboarding and tenant operations

These operations use existing approved APIs plus [ADR0014](../architecture/decisions/0014-guided-onboarding-and-tenant-discovery.md).
They do not authorize public signup, customer import, paid inference or public
deployment. Start with fictional data in an independent pilot.

## Owner and schema preparation

The operator provisions a first tenant/OWNER through the offline
[authentication bootstrap](authentication-operations.md#initial-tenant-and-owner),
not a browser signup or SQL insert. Generate signing keys through that runbook;
do not reuse production keys. Existing users keep their passwords and identities.
Obtain local credentials from the authorized operator; never place passwords,
JWTs or invitation tokens in URLs, Git or shared screenshots/logs.

V9 is required for organization discovery. Back up/review an existing approved
pilot, stop application writes, use its explicit offline migration command, and
validate schema/roles/RLS before startup. Do not edit V1–V8 or migrate a developer
volume as an automated test. For fresh synthetic testing follow
[business pilot operations](business-pilot-operations.md).

## Sign in and choose your organization

Use the existing email/password login. The picker fetches only your current
organizations, names and roles. Choose the intended organization explicitly;
the server verifies current membership before opening it. Refresh organizations
after an invitation or role change. Pagination is bounded; a page is not a global
directory. Names can repeat, so use the optional support identifier when needed.

No memberships: ask an OWNER for a scoped invitation. Existing accounts accept
while signed in; new invited accounts use the separate acceptance form, then
normal login. The raw token is shared through a secure channel, never a URL.
Expired/cancelled/used invitations cannot be redeemed. No email is sent by this
platform. The manual tenant UUID form remains a support fallback and grants no
extra access. Sign out or switch organization to clear prior feature data.

## First-use workflow

Overview explains your verified role and displays a guide based on current bounded
server pages. "Not observed" does not mean a historical record cannot exist.
Refresh overview after making changes elsewhere.

1. Open Agents. OWNER/ADMIN deliberately creates a DRAFT assistant with a clear
   name, purpose and instructions. DRAFT is usable; no publishing step exists.
   MEMBER asks a manager to change configuration.
2. Open Knowledge library, select the intended assistant, and upload authorized
   UTF-8 TXT or selectable-text PDF (≤2 MiB; PDF ≤ 25 pages). Scans/encrypted or damaged
   PDFs are unsupported. The initial 202 means accepted, not indexed.
3. Wait for that document to be READY with nonzero chunks and confirm its agent
   association. Pending work has bounded automatic refresh; use Refresh documents
   after it stops. Association changes do not duplicate the stored source.
4. Open Knowledge search and deliberately search a factual question. This uses
   local semantic retrieval, not hosted generation. Inspect the passage, filename
   and page; a similarity score is not a confidence probability.
5. Conversations preserve your own history, subject to current membership. Create
   and send only deliberately. Hosting must be configured separately by an
   operator; the guide does not know whether it is available. A cost confirmation
   is not evidence of availability. No paid request is dispatched by navigation.
6. Team & Access lets OWNER invite ADMIN/MEMBER and ADMIN invite MEMBER. Copy the
   token once, share securely, then dismiss it. Return to the tab to refresh after
   acceptance, or use Refresh access. Automatic refresh pauses while a token,
   confirmation or write is open and is limited to once per five seconds.
7. Review Tool proposals. PENDING_APPROVAL has no effect. A different authorized
   manager must review the exact payload and approve it. APPROVED still creates
   no task; separate explicit execution creates one OPEN internal task. No customer
   email, WhatsApp or other external delivery occurs. Inspect its durable task ID.

## Failures and safe recovery

| Situation | Action |
| --- | --- |
| Login 503 | Ask the operator to check signing configuration; do not repeatedly retry credentials. |
| Expired JWT 401 | Sign in again; the console clears private state. |
| Membership 403 | Switch tenant, refresh organizations, ask OWNER to check current access. Do not guess other tenant IDs. |
| No matching organization | Check additional pages, refresh after acceptance, or verify the account with the inviter. |
| Document FAILED | Read the repair guidance. Replace malformed/scanned/encrypted input with a supported authorized file. Operator checks services before deliberate Retry processing. |
| No evidence | Check READY documents and associations; read relevant passages before another question. |
| Provider unavailable | Ask the operator; do not infer availability from health or the first-use guide. |
| Rate limit 429 | Stop requests, observe any retry hint, then retry deliberately. Shared daily model budget resets at UTC midnight; refresh is not a quota bypass. |
| Archived agent / closed conversation 409 | Read history; choose an authorized usable agent/new conversation deliberately. Archive/close is terminal. |
| Pending or rejected proposal | Pending needs a different manager; rejection cannot execute. Never bypass approval or change the reviewed arguments. |
| Lost response | Retrieve the existing request/run/proposal outcome first. Conversation request IDs survive navigation in the current tenant session. Status reads dispatch no model. Never automatically create a replacement paid request or proposal. |
| Failed execution | Confirm the durable FAILED result, not an invented successful answer/task. A provider charge may have occurred. Escalate using the safe support ID. |

On narrow screens the table area is focusable: scroll it sideways to reach action
columns, or use keyboard arrows. Technical IDs remain secondary support details.
Source citations identify authorized evidence; they neither certify correctness
nor provide a source-file download endpoint. Do not expose private document bytes
through a guessed URL.

See [pilot/recovery](backup-and-disaster-recovery.md),
[privacy plan](../security/privacy-lifecycle-implementation-plan.md),
[human protocol](../product/human-usability-test-plan.md) and
[hosted experiment plan](../product/hosted-quality-evaluation-plan.md).
