# Tenant access operations

Follow [ADR 0008](../architecture/decisions/0008-tenant-invitations-and-access-management.md), [authentication operations](authentication-operations.md) and [console operations](web-console-operations.md). EAAP-009 adds invite-only accounts, not unrestricted signup. These procedures are for local development; production delivery, identity verification and abuse protection require separate review.

## Local setup and V7

Use the existing ignored `.env`, four distinct database passwords, external signing files and initial OWNER from authentication operations. No invitation configuration, new service or dependency is needed. Never put real passwords/tokens in tracked files, shell tracing, command arguments, screenshots, URLs or shared logs. Share the one-time invitation only through an approved secure external channel; no email is sent.

From the repository root, `./dev.sh up` builds the control plane and applies migrations through V7 using the offline migration profile. It preserves existing data. Configure signing keys separately as described in authentication operations; baseline health alone does not prove login is configured. For an already configured signed stack, an explicit upgrade is:

```sh
docker compose up -d --wait postgres
docker compose build control-plane web
# Export MIGRATION_DB_PASSWORD from your protected environment; Compose's .env
# is not automatically exported into the interactive shell.
docker compose run --rm --no-deps -e MIGRATION_DB_PASSWORD \
  control-plane --spring.profiles.active=migration
# Repeating that command must report no migration necessary.
docker compose -f compose.yaml -f compose.auth.yaml up -d --wait
```

If already using knowledge/execution, retain those configured overlays on the final startup command. Do not change the signing pair or bootstrap an existing email with different UUIDs. Use `./dev.sh bootstrap` only for the initial owner, not invited colleagues. Do not edit V1–V6, reset a volume or grant runtime identity writes. Back up important data before upgrade. V7 creates tables/functions/indexes; normal startup never migrates. Rollback is an operator-reviewed restore or forward migration, not deleting Flyway history.

Open http://127.0.0.1:4173, sign in and enter your tenant UUID. **Team & Access** refreshes current server role and lists members. OWNER can invite ADMIN/MEMBER; ADMIN can invite MEMBER only. MEMBER sees a limited directory with other emails redacted. Tenant lists use size20 by default, maximum100, with bounded pagination.

## Browser invitation walkthrough

1. As OWNER, open Team & Access, enter the colleague's email, select MEMBER and create the invitation. Copy/save the token immediately. Only its SHA-256 digest is stored. Refreshing, dismissing, leaving the view, switching tenant, logout or reload removes the displayed token; list responses cannot recover it. Manual text selection is available if Clipboard API is unavailable. Clipboard/history cleanup remains your responsibility.
2. Share the 43-character token securely. It expires after48hours. At most five live invitations exist per tenant; one per normalized email. Cancel an unused invitation to release a live slot. Expired PENDING rows show `expired=true`; they no longer count as live and need no scheduler.
3. The new colleague opens the login page → **Accept an invitation**, pastes the token, enters its matching email and a new password of16–128characters, then confirms the password. Successful acceptance returns tenant/user metadata and returns to normal login. It does not sign in automatically.
4. The colleague signs in normally and opens the supplied tenant UUID. MEMBER cannot create invitations, approve proposals, change roles or revoke anyone.
5. OWNER selects the colleague's row → **Promote to ADMIN** and confirms. The colleague's same token receives ADMIN permissions on the next server request; Refresh access updates the role shown by Team & Access. OWNER can similarly demote to MEMBER. ADMIN cannot promote, edit ADMIN/OWNER or revoke self.
6. OWNER selects **Revoke membership** and confirms the specific person. The colleague's next request to this tenant returns403. Their global account and other memberships still work; runs, proposals, decisions and access audit history survive.

For a second tenant, its OWNER invites the colleague's existing email. The colleague signs in to the existing account, chooses **Switch tenant** → **Accept an invitation**, and pastes the new token. That form has no password field. Success pre-fills the authorized tenant UUID; **Open workspace** still verifies live membership. An invitation cannot overwrite a current member's role; that returns409. Replaying a consumed token returns410.

## Terminal client without jq or secrets in command arguments

Run this helper in each Bash terminal used for the walkthrough. Python3 uses the standard library only. Input and credentials travel through stdin/environment; no credential is interpolated into a command argument. Run on a trusted local machine without shell tracing or recorded terminal output.

```bash
export API=http://127.0.0.1:8080
set -o pipefail
request() {
  python3 -c '
import json,os,sys,urllib.request,urllib.error
method,path=sys.argv[1:]
body=sys.stdin.buffer.read()
headers={"Content-Type":"application/json"}
if os.environ.get("API_TOKEN"):
    headers["Authorization"]="Bearer "+os.environ["API_TOKEN"]
req=urllib.request.Request(os.environ["API"]+"/api/v1"+path,
    data=body if body else None,headers=headers,method=method)
try:
    with urllib.request.urlopen(req,timeout=30) as r:
        print("HTTP",r.status,file=sys.stderr)
        print(r.read().decode())
except urllib.error.HTTPError as e:
    print("HTTP",e.code,file=sys.stderr)
    print(e.read().decode())
    sys.exit(1)
' "$1" "$2"
}
login() {
  unset API_TOKEN
  read -r -p 'Email: ' LOGIN_EMAIL
  read -r -s -p 'Password: ' LOGIN_PASSWORD; echo
  export LOGIN_EMAIL LOGIN_PASSWORD
  local result
  if ! result=$(python3 -c 'import os,json; print(json.dumps({"email":os.environ["LOGIN_EMAIL"],"password":os.environ["LOGIN_PASSWORD"]}))' |
      request POST /auth/login); then
    unset LOGIN_PASSWORD LOGIN_EMAIL API_TOKEN
    return 1
  fi
  API_TOKEN=$(printf '%s' "$result" | python3 -c 'import json,sys; print(json.load(sys.stdin)["accessToken"])')
  export API_TOKEN
  unset LOGIN_PASSWORD LOGIN_EMAIL result
}
login
read -r -p 'Tenant UUID: ' TENANT_ID
export TENANT_ID
request GET "/tenants/$TENANT_ID/context" </dev/null
request GET "/tenants/$TENANT_ID/members?page=0&size=20" </dev/null
```

In the OWNER terminal, issue an invitation. Save the output token only through your secure delivery channel; the final print is the deliberate one-time display, so do not capture/share your terminal.

```bash
read -r -p 'Colleague email: ' INVITED_EMAIL
export INVITED_EMAIL
INVITATION=$(python3 -c 'import json,os; print(json.dumps({"email":os.environ["INVITED_EMAIL"],"role":"MEMBER"}))' |
  request POST "/tenants/$TENANT_ID/invitations")
INVITATION_ID=$(printf '%s' "$INVITATION" | python3 -c 'import json,sys; print(json.load(sys.stdin)["invitation"]["id"])')
INVITATION_TOKEN=$(printf '%s' "$INVITATION" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
printf '%s\n' "$INVITATION_TOKEN"
unset INVITATION INVITATION_TOKEN
request GET "/tenants/$TENANT_ID/invitations?page=0&size=20" </dev/null
```

For **new-account acceptance**, open a separate terminal, define the helper above without calling `login`, then run:

```bash
unset API_TOKEN
read -r -s -p 'Invitation token: ' INVITATION_TOKEN; echo
read -r -p 'Invited email: ' INVITED_EMAIL
read -r -s -p 'New password (16–128 characters): ' NEW_PASSWORD; echo
read -r -s -p 'Confirm password: ' CONFIRM_PASSWORD; echo
if [ "$NEW_PASSWORD" = "$CONFIRM_PASSWORD" ]; then
  export INVITATION_TOKEN INVITED_EMAIL NEW_PASSWORD
  python3 -c 'import os,json; print(json.dumps({"token":os.environ["INVITATION_TOKEN"],"email":os.environ["INVITED_EMAIL"],"password":os.environ["NEW_PASSWORD"]}))' |
    request POST /invitations/accept-new
else
  echo 'Passwords do not match.'
fi
unset INVITATION_TOKEN NEW_PASSWORD CONFIRM_PASSWORD
# Expect201 with invitationId, tenantId, userId, role; no accessToken.
# Now call login and test the returned tenant UUID with GET /context.
```

For **existing-account acceptance**, authenticate as the invited existing email with `login`; keep that same account's token and use only the invitation token:

```bash
read -r -s -p 'Invitation token: ' INVITATION_TOKEN; echo
export INVITATION_TOKEN
python3 -c 'import os,json; print(json.dumps({"token":os.environ["INVITATION_TOKEN"]}))' |
  request POST /invitations/accept-existing
unset INVITATION_TOKEN
# Expect200. Open the returned tenant with the same API_TOKEN.
```

In the OWNER terminal, select the target `userId` from the tenant member list. The following are separate deliberate administrative actions; do not execute revocation until promotion/approval checks are complete:

```bash
read -r -p 'Target member user UUID: ' TARGET_USER_ID
printf '%s' '{"role":"ADMIN"}' |
  request PUT "/tenants/$TENANT_ID/members/$TARGET_USER_ID/role"
# Colleague terminal: GET /context with the SAME API_TOKEN now returns ADMIN.
# Optional demotion:
printf '%s' '{"role":"MEMBER"}' |
  request PUT "/tenants/$TENANT_ID/members/$TARGET_USER_ID/role"
# Revocation (confirm the target first):
request DELETE "/tenants/$TENANT_ID/members/$TARGET_USER_ID" </dev/null
# Colleague terminal: GET /context now returns403 for this tenant.
# Cancel an unused pending invitation, including an effectively expired one:
request POST "/tenants/$TENANT_ID/invitations/$INVITATION_ID/cancel" </dev/null
```

## Two-person approval using an invited account

Create a MEMBER through the invitation workflow above. OWNER creates an agent and an unapproved proposal following [tool operations](tool-approval-operations.md), without paid AI generation. The invited MEMBER's approval must return403. OWNER promotes that same colleague to ADMIN. The colleague retains their original JWT, retrieves the proposal to review exact arguments, rationale and `payloadDigest`, and deliberately submits:

```bash
read -r -p 'Reviewed proposal UUID: ' PROPOSAL_ID
request GET "/tenants/$TENANT_ID/tool-proposals/$PROPOSAL_ID" </dev/null
read -r -p 'Reviewed payload digest: ' EXPECTED_DIGEST
export EXPECTED_DIGEST
python3 -c 'import json,os; print(json.dumps({"expectedPayloadDigest":os.environ["EXPECTED_DIGEST"]}))' |
  request POST "/tenants/$TENANT_ID/tool-proposals/$PROPOSAL_ID/approve"
```

The proposal creator must be a different user. Approval creates no task. Execution remains a separate confirmed action described in tool operations. Revoke the invited membership afterward and confirm its tenant request returns403; historical attribution must remain present. The isolated acceptance run verified MEMBER403 followed by ADMIN200 approval using the same JWT, then revocation403, without a hosted provider call.

## Troubleshooting and security boundary

- 400: check exact fields, well-formed email, 16–128 password policy, page bounds and4096-byte body cap. Do not add actor/tenant/role authority fields to acceptance JSON.
- 401: sign in normally or renew an expired/disabled-user session. New acceptance is anonymous; existing acceptance requires Bearer.
- 403: verify current tenant membership and permitted role. OWNER/self protections cannot be bypassed through this feature.
- 409: duplicate live email, already-existing membership or terminal cancellation. Refresh metadata; do not overwrite identities or retry mutations automatically.
- 410: invitation unavailable. This deliberately combines invalid/expired/cancelled/consumed/wrong-recipient/new-account-already-exists cases. Existing accounts must use authenticated acceptance. A removed/demoted inviter or an invitation predating membership revocation also becomes unavailable. Cancel the stale invitation and deliberately issue a fresh one if reinvitation is intended.
- 429: five live tenant invitations, five acceptance attempts per peer/minute, twenty per process/minute, or two concurrent acceptance slots. Wait and inspect metadata rather than looping. The preview proxy makes browser users share one peer address. Limits are local memory, reset on restart and are not distributed production protection.
- 500: retain the safe X-Request-ID and inspect bounded service diagnostics/schema privileges. A lock/DB deadline can also fail a request. Never enable SQL parameter dumps or print tokens/hashes while troubleshooting. Runtime credentials must not gain direct identity/invitation/audit writes.
- 503 on login: follow signing configuration in authentication operations. A healthy container alone does not establish signing readiness.

Actor UUID parameters and transaction-local tenant settings in SECURITY DEFINER calls are trusted application assertions. PostgreSQL cannot authenticate that UUID cryptographically. Arbitrary SQL access/runtime compromise can impersonate an actor; no stronger guarantee is claimed. Audit tables expose no runtime read/write API. Privileged operators may inspect metadata/audit in the correct tenant context with protected tools; do not copy tokens/passwords/hashes into audit evidence.

## Verification

From the repository root, with Java21, Python3.12/uv, Node22 and Docker available:

```sh
(cd services/control-plane && ./mvnw -B -ntp verify)
(cd services/agent-runtime && uv run --frozen ruff check . && uv run --frozen ruff format --check . && uv run --frozen pytest)
(cd apps/web && npm run lint && npm run typecheck && npm test && npm run build)
git diff --check
```

Java Testcontainers uses synthetic disposable databases: fresh V1–V7, repeated no-op, V6→V7, function ACL/search_path/RLS checks, direct runtime abuse, expiry, replay, concurrent redemption/issuance/cancellation/revocation, rollback and retained history. It never resets the normal development volume. For manual acceptance, use a separate Compose project, fresh project volume and unused loopback ports; bootstrap synthetic OWNERs through the offline profile, then create colleagues only through invitations. Remove only that disposable project's volume afterward. [EAAP-009 evidence](../tasks/EAAP-009.md) records actual HTTP/Chromium results and remaining limitations.
