import { useState } from "react";
import type { ApiClient } from "../api/client";
import { acceptedContract } from "../api/contracts";
import type { AcceptedInvitation } from "../api/contracts";
import { useAction } from "../components/hooks";
import { ErrorNotice, Field, Id } from "../components/ui";
export function AcceptInvitation({
  api,
  existing,
  onBack,
  onAccepted,
}: {
  api: ApiClient;
  existing: boolean;
  onBack: () => void;
  onAccepted: (result: AcceptedInvitation) => void;
}) {
  const [token, setToken] = useState(""),
    [email, setEmail] = useState(""),
    [password, setPassword] = useState(""),
    [confirmation, setConfirmation] = useState(""),
    [invalid, setInvalid] = useState(false);
  const action = useAction();
  return (
    <main className="auth-wrap">
      <section className="auth-card">
        <p className="eyebrow">INVITATION ACCESS</p>
        <h1>{existing ? "Join a tenant" : "Accept your invitation"}</h1>
        <p>
          {existing
            ? "Accept using your authenticated account. Your email must match the invitation. Your password remains unchanged."
            : "New accounts require a valid invitation. Already have an account? Return to sign in and accept after authentication."}
        </p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (
              !existing &&
              (password !== confirmation || password.length < 16)
            ) {
              setInvalid(true);
              return;
            }
            setInvalid(false);
            void action.act(
              (signal) =>
                api.request(
                  `/api/v1/invitations/${existing ? "accept-existing" : "accept-new"}`,
                  acceptedContract,
                  {
                    method: "POST",
                    json: existing ? { token } : { token, email, password },
                    signal,
                  },
                ),
              (result) => {
                setToken("");
                setPassword("");
                setConfirmation("");
                onAccepted(result);
              },
            );
          }}
        >
          <fieldset disabled={action.busy}>
            <Field
              label="Invitation token"
              hint="Paste the token received from your inviter. Never put it in a URL."
            >
              <input
                autoComplete="off"
                spellCheck={false}
                required
                minLength={43}
                maxLength={43}
                pattern="(?:[A-Za-z0-9_]|-){43}"
                value={token}
                onChange={(e) => setToken(e.target.value)}
              />
            </Field>
            {!existing && (
              <>
                <Field label="Invited email">
                  <input
                    autoComplete="username"
                    required
                    type="email"
                    maxLength={254}
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                </Field>
                <Field
                  label="New password"
                  hint="16–128 characters. This never replaces an existing account password."
                >
                  <input
                    autoComplete="new-password"
                    type="password"
                    required
                    minLength={16}
                    maxLength={128}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    aria-invalid={invalid}
                    aria-describedby={invalid ? "accept-error" : undefined}
                  />
                </Field>
                <Field label="Confirm password">
                  <input
                    autoComplete="new-password"
                    type="password"
                    required
                    minLength={16}
                    maxLength={128}
                    value={confirmation}
                    onChange={(e) => setConfirmation(e.target.value)}
                    aria-invalid={invalid}
                    aria-describedby={invalid ? "accept-error" : undefined}
                  />
                </Field>
                {invalid && (
                  <p role="alert" id="accept-error">
                    Use 16–128 characters and matching passwords.
                  </p>
                )}
              </>
            )}
            <ErrorNotice error={action.error} />
            <button className="primary" disabled={action.busy}>
              {action.busy ? "Accepting…" : "Accept invitation"}
            </button>
            <button type="button" onClick={onBack} disabled={action.busy}>
              Back
            </button>
          </fieldset>
        </form>
      </section>
    </main>
  );
}
export function InvitationAccepted({ result }: { result: AcceptedInvitation }) {
  return (
    <p role="status">
      Invitation accepted. Tenant: <Id value={result.tenantId} />. Sign in
      through the normal login and select this tenant.
    </p>
  );
}
