import { useCallback, useEffect, useState } from "react";
import { ApiClient } from "./api/client";
import type { TenantContext } from "./api/contracts";
import { MemorySession } from "./auth/session";
import { AcceptInvitation, InvitationAccepted } from "./auth/AcceptInvitation";
import type { AcceptedInvitation } from "./api/contracts";
import { Login } from "./auth/Login";
import { TenantSelection } from "./auth/TenantSelection";
import { Console } from "./app/Console";
type AuthState = {
  session: MemorySession;
  client: ApiClient;
  context?: TenantContext;
  workspaceName?: string;
  scope: number;
};
function App() {
  const [auth, setAuth] = useState<AuthState | null>(null),
    [message, setMessage] = useState(""),
    [acknowledged, setAcknowledged] = useState(false);
  const [accepting, setAccepting] = useState(false),
    [accepted, setAccepted] = useState<AcceptedInvitation | null>(null);
  const onContext = useCallback(
    (context: TenantContext, client: ApiClient) =>
      setAuth((current) =>
        current &&
        current.client === client &&
        current.context?.role !== context.role
          ? { ...current, context }
          : current,
      ),
    [],
  );
  const [loginClient] = useState(() => new ApiClient(null));
  useEffect(() => {
    if (!auth) return;
    const timer = setTimeout(
      () => {
        auth.session.dispose();
        setAuth(null);
        setAcknowledged(false);
        setMessage("Your session expired. Please sign in again.");
      },
      Math.max(0, auth.session.expiresAt - Date.now()),
    );
    return () => clearTimeout(timer);
  }, [auth]);
  function end(reason: string) {
    auth?.session.dispose();
    setAuth(null);
    setAcknowledged(false);
    setMessage(reason);
  }
  function login(token: string, seconds: number) {
    const session = new MemorySession(token, seconds);
    const expired = () => {
      session.dispose();
      setAuth((current) => (current?.session === session ? null : current));
      setAcknowledged(false);
      setMessage("Your session expired. Please sign in again.");
    };
    setMessage("");
    setAccepted(null);
    setAuth({ session, client: session.scope(expired), scope: 0 });
  }
  if (!auth) {
    if (accepting)
      return (
        <AcceptInvitation
          api={loginClient}
          existing={false}
          onBack={() => setAccepting(false)}
          onAccepted={(result) => {
            setAccepting(false);
            setAccepted(result);
            setMessage(
              "Account created. Sign in with your existing login form.",
            );
          }}
        />
      );
    return (
      <>
        {accepted && <InvitationAccepted result={accepted} />}
        <Login
          api={loginClient}
          message={message}
          onLogin={login}
          onAccept={() => {
            setAccepted(null);
            setAccepting(true);
          }}
        />
      </>
    );
  }
  if (!auth.context)
    return (
      <TenantSelection
        key={auth.scope}
        api={auth.client}
        onLogout={() => end("Signed out.")}
        onSelect={(context, workspaceName) =>
          setAuth((current) =>
            current?.client === auth.client
              ? { ...current, context, workspaceName }
              : current,
          )
        }
      />
    );
  return (
    <Console
      key={auth.scope}
      onContext={onContext}
      client={auth.client}
      context={auth.context}
      workspaceName={auth.workspaceName}
      acknowledged={acknowledged}
      onAcknowledge={() => setAcknowledged(true)}
      onLogout={() => end("Signed out.")}
      onSwitch={() => {
        setAcknowledged(false);
        auth.session.clearScopes();
        const session = auth.session;
        const expired = () => {
          session.dispose();
          setAuth((current) => (current?.session === session ? null : current));
          setAcknowledged(false);
          setMessage("Your session expired. Please sign in again.");
        };
        setAuth({
          session,
          client: session.scope(expired),
          scope: auth.scope + 1,
        });
      }}
    />
  );
}
export default App;
