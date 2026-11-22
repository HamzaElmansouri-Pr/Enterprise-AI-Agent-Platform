import { AcceptInvitation } from "./AcceptInvitation";
import { useState } from "react";
import { ApiClient } from "../api/client";
import { contextContract, isUuid } from "../api/contracts";
import type { TenantContext } from "../api/contracts";
import { useAction } from "../components/hooks";
import { ErrorNotice, Field } from "../components/ui";
export function TenantSelection({
  api,
  onSelect,
  onLogout,
}: {
  api: ApiClient;
  onSelect: (context: TenantContext) => void;
  onLogout: () => void;
}) {
  const [tenant, setTenant] = useState(""),
    [invalid, setInvalid] = useState(false);
  const [accepting, setAccepting] = useState(false),
    [notice, setNotice] = useState("");
  const action = useAction();
  if (accepting)
    return (
      <AcceptInvitation
        existing
        api={api}
        onBack={() => setAccepting(false)}
        onAccepted={(result) => {
          setAccepting(false);
          setTenant(result.tenantId);
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
        <h1>Select your tenant</h1>
        <p>
          Enter an existing tenant UUID. Spring will verify your current
          membership.
        </p>
        {notice && <p role="status">{notice}</p>}
        <button onClick={() => setAccepting(true)}>Accept an invitation</button>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (!isUuid(tenant.trim())) {
              setInvalid(true);
              return;
            }
            setInvalid(false);
            void action.act(
              (signal) =>
                api.request(
                  `/api/v1/tenants/${tenant.trim()}/context`,
                  contextContract,
                  { signal },
                ),
              onSelect,
            );
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
          <ErrorNotice error={action.error} />
          <button className="primary" disabled={action.busy}>
            {action.busy ? "Verifying…" : "Open workspace"}
          </button>
          <button type="button" onClick={onLogout}>
            Sign out
          </button>
        </form>
      </section>
    </main>
  );
}
