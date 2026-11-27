import { TableScroll } from "../../components/ui";
import { useCallback, useEffect, useRef, useState } from "react";
import type { ApiClient } from "../../api/client";
import { contextContract } from "../../api/contracts";
import type {
  Invitation,
  IssuedInvitation,
  Member,
  TenantContext,
} from "../../api/contracts";
import type { TenantApi } from "../../api/tenant";
import { useAction, useRemote } from "../../components/hooks";
import {
  Confirm,
  Empty,
  ErrorNotice,
  Field,
  Heading,
  Id,
  Loading,
  Pager,
  Status,
  When,
} from "../../components/ui";
type Confirmation =
  | { kind: "role" | "revoke"; member: Member }
  | { kind: "cancel"; invitation: Invitation };
export function Team({
  api,
  onContext,
}: {
  api: TenantApi;
  onContext: (context: TenantContext, client: ApiClient) => void;
}) {
  const [memberPage, setMemberPage] = useState(0),
    [invitePage, setInvitePage] = useState(0),
    [email, setEmail] = useState(""),
    [role, setRole] = useState<"ADMIN" | "MEMBER">("MEMBER"),
    [issued, setIssued] = useState<IssuedInvitation | null>(null),
    [confirmation, setConfirmation] = useState<Confirmation | null>(null),
    [copyNotice, setCopyNotice] = useState("");
  const action = useAction();
  const remote = useRemote(
    useCallback(
      async (signal: AbortSignal) => {
        const context = await api.client.request(
          `${api.base}/context`,
          contextContract,
          { signal },
        );
        const [members, invitations] = await Promise.all([
          api.members(memberPage, signal),
          context.role === "MEMBER"
            ? Promise.resolve(null)
            : api.invitations(invitePage, signal),
        ]);
        signal.throwIfAborted();
        onContext(context, api.client);
        return { context, members, invitations };
      },
      [api, memberPage, invitePage, onContext],
    ),
  );
  const data = remote.data;
  const lastRefresh = useRef(0);
  const refreshDirectory = remote.refresh;
  useEffect(() => {
    const refreshOnReturn = () => {
      if (
        document.visibilityState !== "visible" ||
        action.busy ||
        issued ||
        confirmation ||
        Date.now() - lastRefresh.current < 5000
      )
        return;
      lastRefresh.current = Date.now();
      refreshDirectory();
    };
    window.addEventListener("focus", refreshOnReturn);
    document.addEventListener("visibilitychange", refreshOnReturn);
    return () => {
      window.removeEventListener("focus", refreshOnReturn);
      document.removeEventListener("visibilitychange", refreshOnReturn);
    };
  }, [refreshDirectory, action.busy, issued, confirmation]);
  function refresh() {
    setIssued(null);
    setCopyNotice("");
    remote.refresh();
  }
  function finished() {
    setConfirmation(null);
    refresh();
    action.setNotice(
      "Access change saved. Permissions apply on the next server request.",
    );
  }
  function confirm() {
    if (!confirmation) return;
    void action.act<unknown>(
      (signal) =>
        confirmation.kind === "cancel"
          ? api.cancelInvitation(confirmation.invitation.id, signal)
          : confirmation.kind === "revoke"
            ? api.revokeMember(confirmation.member.userId, signal)
            : api.changeRole(
                confirmation.member.userId,
                confirmation.member.role === "ADMIN" ? "MEMBER" : "ADMIN",
                signal,
              ),
      finished,
    );
  }
  return (
    <>
      <Heading
        title="Team & Access"
        description="Invite colleagues and manage current tenant membership."
        action={
          <button disabled={action.busy} onClick={refresh}>
            Refresh access
          </button>
        }
      />
      <ErrorNotice error={remote.error} />
      <p>
        Access refreshes when you return to this tab (at most once per five
        seconds). Use Refresh access after a colleague accepts. Copy and dismiss
        a newly issued token first; automatic refresh pauses while it or a
        confirmation is open.
      </p>
      {remote.loading && <Loading />}
      {data && (
        <>
          <section className="card">
            <p>
              Your current role: <strong>{data.context.role}</strong>
            </p>
            <Id value={data.context.userId} />
            <p>
              OWNER memberships are protected. There is no ownership transfer or
              global identity editing.
            </p>
          </section>
          {data.context.role !== "MEMBER" && (
            <>
              <form
                className="card form-width"
                onSubmit={(e) => {
                  e.preventDefault();
                  setIssued(null);
                  setCopyNotice("");
                  void action.act(
                    (signal) => api.invite(email, role, signal),
                    (result) => {
                      setIssued(result);
                      setEmail("");
                      remote.refresh();
                    },
                  );
                }}
              >
                <fieldset disabled={action.busy}>
                  <h2>Invite a colleague</h2>
                  <p>
                    Invitations expire after 48 hours. At most five live
                    invitations are allowed. No email is sent.
                  </p>
                  <Field label="Colleague email">
                    <input
                      required
                      type="email"
                      maxLength={254}
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                    />
                  </Field>
                  <Field label="Invitation role">
                    <select
                      value={role}
                      onChange={(e) =>
                        setRole(e.target.value as "ADMIN" | "MEMBER")
                      }
                    >
                      <option>MEMBER</option>
                      {data.context.role === "OWNER" && <option>ADMIN</option>}
                    </select>
                  </Field>
                  <button className="primary" disabled={action.busy}>
                    {action.busy ? "Creating…" : "Create invitation"}
                  </button>
                </fieldset>
              </form>
              {issued && (
                <section className="card" aria-label="One-time invitation">
                  <h2>Save this invitation token now</h2>
                  <p>
                    It will never be displayed again after leaving this view.
                    Share it through a secure external channel. Never put it in
                    a URL.
                  </p>
                  <Field label="One-time invitation token">
                    <input
                      readOnly
                      autoComplete="off"
                      spellCheck={false}
                      value={issued.token}
                    />
                  </Field>
                  <p>
                    Expires: <When value={issued.invitation.expiresAt} />
                  </p>
                  <div className="actions">
                    <button
                      onClick={() => {
                        if (!navigator.clipboard?.writeText) {
                          setCopyNotice(
                            "Copy unavailable. Select the token manually.",
                          );
                          return;
                        }
                        void navigator.clipboard.writeText(issued.token).then(
                          () =>
                            setCopyNotice("Token copied. Share it securely."),
                          () =>
                            setCopyNotice(
                              "Copy unavailable. Select the token manually.",
                            ),
                        );
                      }}
                    >
                      Copy token
                    </button>
                    <button
                      onClick={() => {
                        setIssued(null);
                        setCopyNotice("");
                      }}
                    >
                      Dismiss token
                    </button>
                  </div>
                  {copyNotice && <p role="status">{copyNotice}</p>}
                </section>
              )}
            </>
          )}
          <h2>Current members</h2>
          <TableScroll>
            <table>
              <thead>
                <tr>
                  <th>Member</th>
                  <th>Role</th>
                  <th>Joined</th>
                  <th>Permitted actions</th>
                </tr>
              </thead>
              <tbody>
                {data.members.items.map((m) => {
                  const other = m.userId !== data.context.userId,
                    editable = other && m.role !== "OWNER",
                    canRevoke =
                      editable &&
                      (data.context.role === "OWNER" ||
                        (data.context.role === "ADMIN" && m.role === "MEMBER"));
                  return (
                    <tr key={m.userId}>
                      <td>
                        {m.email && <strong>{m.email}</strong>}
                        <Id value={m.userId} />
                        {!other && <small>Your membership</small>}
                      </td>
                      <td>
                        <Status value={m.role} />
                      </td>
                      <td>
                        <When value={m.createdAt} />
                      </td>
                      <td>
                        <div className="actions">
                          {editable && data.context.role === "OWNER" && (
                            <button
                              disabled={action.busy}
                              onClick={() =>
                                setConfirmation({ kind: "role", member: m })
                              }
                            >
                              {m.role === "MEMBER"
                                ? "Promote to ADMIN"
                                : "Demote to MEMBER"}
                            </button>
                          )}
                          {canRevoke && (
                            <button
                              className="danger"
                              disabled={action.busy}
                              onClick={() =>
                                setConfirmation({ kind: "revoke", member: m })
                              }
                            >
                              Revoke membership
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </TableScroll>
          {!data.members.items.length && (
            <Empty>No members on this page.</Empty>
          )}
          <Pager
            page={memberPage}
            hasNext={data.members.hasNext}
            onChange={(page) => {
              setIssued(null);
              setMemberPage(page);
            }}
          />
          {data.invitations && (
            <>
              <h2>Invitations</h2>
              <TableScroll>
                <table>
                  <thead>
                    <tr>
                      <th>Recipient / role</th>
                      <th>State</th>
                      <th>Expires / decision</th>
                      <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.invitations.items.map((i) => (
                      <tr key={i.id}>
                        <td>
                          <strong>{i.email}</strong>
                          <small>{i.role}</small>
                          <Id value={i.id} />
                        </td>
                        <td>
                          <Status value={i.status} />
                          {i.expired && (
                            <small>
                              Effectively expired · cannot be accepted
                            </small>
                          )}
                        </td>
                        <td>
                          <When value={i.expiresAt} />
                          {i.acceptedBy && (
                            <small>
                              Accepted by <Id value={i.acceptedBy} />
                            </small>
                          )}
                          {i.cancelledBy && (
                            <small>
                              Cancelled by <Id value={i.cancelledBy} />
                            </small>
                          )}
                        </td>
                        <td>
                          {i.status === "PENDING" &&
                            (data.context.role === "OWNER" ||
                              (i.role === "MEMBER" &&
                                i.invitedBy === data.context.userId)) && (
                              <button
                                disabled={action.busy}
                                onClick={() =>
                                  setConfirmation({
                                    kind: "cancel",
                                    invitation: i,
                                  })
                                }
                              >
                                Cancel invitation
                              </button>
                            )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TableScroll>
              {!data.invitations.items.length && (
                <Empty>No invitations on this page.</Empty>
              )}
              <Pager
                page={invitePage}
                hasNext={data.invitations.hasNext}
                onChange={(page) => {
                  setIssued(null);
                  setInvitePage(page);
                }}
              />
            </>
          )}
          <ErrorNotice error={action.error} />
          {action.notice && <p role="status">{action.notice}</p>}
          {confirmation && (
            <Confirm
              title={
                confirmation.kind === "cancel"
                  ? "Cancel this invitation?"
                  : confirmation.kind === "revoke"
                    ? "Revoke tenant membership?"
                    : "Change this member’s role?"
              }
              busy={action.busy}
              onCancel={() => setConfirmation(null)}
              onConfirm={confirm}
              confirm={
                confirmation.kind === "cancel"
                  ? "Cancel invitation"
                  : confirmation.kind === "revoke"
                    ? "Revoke membership"
                    : "Change role"
              }
            >
              {confirmation.kind === "cancel" ? (
                <p>
                  {confirmation.invitation.email} ·{" "}
                  {confirmation.invitation.role}. This token will no longer be
                  usable.
                </p>
              ) : (
                <>
                  <p>
                    {confirmation.member.email ?? confirmation.member.userId}
                  </p>
                  <Id value={confirmation.member.userId} />
                  {confirmation.kind === "revoke" ? (
                    <p>
                      Tenant access ends on the next request. The global account
                      and historical audit records remain. Earlier invitations
                      cannot restore this membership.
                    </p>
                  ) : (
                    <p>
                      {confirmation.member.role} →{" "}
                      {confirmation.member.role === "ADMIN"
                        ? "MEMBER"
                        : "ADMIN"}
                      . Existing JWTs use the new permission on their next
                      request.
                    </p>
                  )}
                </>
              )}
              <ErrorNotice error={action.error} />
            </Confirm>
          )}
        </>
      )}
    </>
  );
}
