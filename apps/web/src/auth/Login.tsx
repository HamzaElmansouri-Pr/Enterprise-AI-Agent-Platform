import { useState } from "react";
import { ApiError } from "../api/client";
import type { ApiClient } from "../api/client";
import { tokenContract } from "../api/contracts";
import { useAction } from "../components/hooks";
import { ErrorNotice, Field } from "../components/ui";
export function Login({
  api,
  message,
  onLogin,
  onAccept,
}: {
  api: ApiClient;
  message: string;
  onLogin: (token: string, expires: number) => void;
  onAccept?: () => void;
}) {
  const [email, setEmail] = useState(""),
    [password, setPassword] = useState("");
  const action = useAction();
  return (
    <main className="auth-wrap">
      <section className="auth-card">
        <div className="brand-mark">E</div>
        <p className="eyebrow">ELMA CORE / OPERATIONS</p>
        <h1>Welcome back</h1>
        <p>Sign in to your tenant operations console.</p>
        {message && (
          <p role="status" className="alert">
            {message}
          </p>
        )}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void action.act(
              (signal) =>
                api.request("/api/v1/auth/login", tokenContract, {
                  method: "POST",
                  json: { email, password },
                  signal,
                }),
              (result) => {
                setPassword("");
                onLogin(result.accessToken, result.expiresIn);
              },
            );
          }}
        >
          <Field label="Email">
            <input
              autoComplete="username"
              type="email"
              required
              maxLength={254}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </Field>
          <Field label="Password">
            <input
              autoComplete="current-password"
              type="password"
              required
              maxLength={128}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </Field>
          {action.error instanceof ApiError && action.error.status === 401 ? (
            <p role="alert">Email or password is incorrect.</p>
          ) : (
            <ErrorNotice error={action.error} />
          )}
          <button className="primary full" disabled={action.busy}>
            {action.busy ? "Signing in…" : "Sign in"}
          </button>
        </form>
        {onAccept && <button onClick={onAccept}>Accept an invitation</button>}
        <p className="muted">
          Your session stays in memory. Reloading this page requires signing in
          again.
        </p>
      </section>
    </main>
  );
}
