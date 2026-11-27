import { AcceptInvitation } from "./AcceptInvitation";
import { useCallback, useState } from "react";
import { ApiClient } from "../api/client";
import {
  contextContract,
  isUuid,
  pageContract,
  workspaceContract,
} from "../api/contracts";
import type { TenantContext } from "../api/contracts";
import { useAction, useRemote } from "../components/hooks";
import { ErrorNotice, Field, Id, Loading, Pager } from "../components/ui";
export function TenantSelection({
  api,
  onSelect,
  onLogout,
}: {
  api: ApiClient;
  onSelect: (context: TenantContext, name?: string) => void;
  onLogout: () => void;
}) {
  const [tenant, setTenant] = useState(""),
    [invalid, setInvalid] = useState(false);
  const [accepting, setAccepting] = useState(false),
    [notice, setNotice] = useState("");
  const action = useAction();
  const [page, setPage] = useState(0);
  const workspaces = useRemote(
    useCallback(
      (signal: AbortSignal) =>
        api.request(
          `/api/v1/me/tenants?page=${page}&size=20`,
          pageContract(workspaceContract),
          { signal },
        ),
      [api, page],
    ),
  );
  function open(id: string, name?: string) {
    void action.act(
      (signal) =>
        api.request(`/api/v1/tenants/${id}/context`, contextContract, {
          signal,
        }),
      (context) => onSelect(context, name),
    );
  }
  if (accepting)
    return (
      <AcceptInvitation
        existing
        api={api}
        onBack={() => setAccepting(false)}
        onAccepted={(result) => {
          setAccepting(false);
          setTenant(result.tenantId);
          setPage(0);
          workspaces.refresh();
          setNotice(
            "Invitation accepted. Open workspace to verify your membership.",
          );
        }}
      />
    );
  return (
    <main className="auth-wrap">
      <section className="auth-card">
        <p className="eyebrow">WORKSPACE ACCESS</p>
        <h1>Choose your organization</h1>
        <p>
          Choose your organization. Your current membership is verified before
          the workspace opens.
        </p>
        <button disabled={action.busy} onClick={workspaces.refresh}>
          Refresh organizations
        </button>
        <ErrorNotice error={workspaces.error} />
        {workspaces.loading && <Loading />}
        {workspaces.data && (
          <section aria-label="Your organizations">
            {!workspaces.data.items.length && (
              <p>
                No organizations on this page. Ask an organization owner for an
                invitation, or refresh after accepting one.
              </p>
            )}
            {workspaces.data.items.map((workspace) => (
              <div className="card" key={workspace.tenantId}>
                <h2>{workspace.name}</h2>
                <p>
                  Your current role: <strong>{workspace.role}</strong>
                </p>
                <details>
                  <summary>Support identifier</summary>
                  <Id value={workspace.tenantId} />
                </details>
                <button
                  className="primary"
                  disabled={action.busy}
                  onClick={() => open(workspace.tenantId, workspace.name)}
                >
                  Open {workspace.name}
                </button>
              </div>
            ))}
            <Pager
              page={page}
              hasNext={workspaces.data.hasNext}
              onChange={setPage}
            />
          </section>
        )}
        <ErrorNotice error={action.error} />
        {!!action.error && (
          <p>
            Access may have changed. Refresh organizations or ask the owner to
            check your membership.
          </p>
        )}
        {notice && <p role="status">{notice}</p>}
        <button onClick={() => setAccepting(true)}>Accept an invitation</button>
        <details>
          <summary>Use a tenant UUID (support fallback)</summary>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              if (!isUuid(tenant.trim())) {
                setInvalid(true);
                return;
              }
              setInvalid(false);
              open(tenant.trim());
            }}
          >
            <Field label="Tenant UUID">
              <input
                required
                value={tenant}
                aria-invalid={invalid}
                aria-describedby={invalid ? "tenant-error" : undefined}
                onChange={(e) => setTenant(e.target.value)}
              />
            </Field>
            {invalid && (
              <p id="tenant-error" role="alert">
                Enter a valid UUID.
              </p>
            )}
            <button className="primary" disabled={action.busy}>
              {action.busy ? "Verifying…" : "Open workspace"}
            </button>
          </form>
        </details>
        <button type="button" onClick={onLogout}>
          Sign out
        </button>
      </section>
    </main>
  );
}
