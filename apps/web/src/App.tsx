import { useEffect, useState } from "react";
import { ApiClient } from "./api/client";
import type { TenantContext } from "./api/contracts";
import { MemorySession } from "./auth/session";
import { Login } from "./auth/Login";
import { TenantSelection } from "./auth/TenantSelection";
import { Console } from "./app/Console";
type AuthState = {
  session: MemorySession;
  client: ApiClient;
  context?: TenantContext;
  scope: number;
};
function App() {
  const [auth, setAuth] = useState<AuthState | null>(null),
    [message, setMessage] = useState(""),
    [acknowledged, setAcknowledged] = useState(false);
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
    setAuth({ session, client: session.scope(expired), scope: 0 });
  }
  if (!auth)
    return <Login api={loginClient} message={message} onLogin={login} />;
  if (!auth.context)
    return (
      <TenantSelection
        key={auth.scope}
        api={auth.client}
        onLogout={() => end("Signed out.")}
        onSelect={(context) => setAuth({ ...auth, context })}
      />
    );
  return (
    <Console
      key={auth.scope}
      client={auth.client}
      context={auth.context}
      acknowledged={acknowledged}
      onAcknowledge={() => setAcknowledged(true)}
      onLogout={() => end("Signed out.")}
      onSwitch={() => {
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
