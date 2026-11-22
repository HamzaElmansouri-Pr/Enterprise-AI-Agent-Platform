import { useMemo, useState } from "react";
import type { ApiClient } from "../api/client";
import type { TenantContext } from "../api/contracts";
import { TenantApi } from "../api/tenant";
import { Dashboard } from "../features/dashboard/Dashboard";
import { Agents } from "../features/agents/Agents";
import { Knowledge, Search } from "../features/knowledge/Knowledge";
import { Executions } from "../features/executions/Executions";
import { Approvals } from "../features/approvals/Approvals";
import { Tasks } from "../features/admissions/Tasks";
const pages = [
  ["dashboard", "Overview", "01"],
  ["agents", "Agents", "02"],
  ["knowledge", "Knowledge library", "03"],
  ["search", "Knowledge search", "04"],
  ["executions", "Ask an agent", "05"],
  ["approvals", "Tool proposals", "06"],
  ["tasks", "Follow-up tasks", "07"],
];
export function Console({
  client,
  context,
  onSwitch,
  onLogout,
  acknowledged,
  onAcknowledge,
}: {
  client: ApiClient;
  context: TenantContext;
  onSwitch: () => void;
  onLogout: () => void;
  acknowledged: boolean;
  onAcknowledge: () => void;
}) {
  const [page, setPage] = useState("dashboard");
  const api = useMemo(
    () => new TenantApi(client, context.tenantId),
    [client, context.tenantId],
  );
  const manager = context.role !== "MEMBER";
  return (
    <div className="console">
      <a className="skip-link" href="#workspace-main">
        Skip to content
      </a>
      <aside className="sidebar">
        <div className="wordmark">
          <span className="brand-mark">E</span>
          <div>
            ELMA CORE<small>AGENT OPERATIONS</small>
          </div>
        </div>
        <p className="nav-label">WORKSPACE</p>
        <nav aria-label="Workspace navigation">
          {pages.map(([key, label, number]) => (
            <button
              key={key}
              aria-current={page === key ? "page" : undefined}
              onClick={() => setPage(key)}
            >
              <span aria-hidden="true">{number}</span>
              {label}
            </button>
          ))}
        </nav>
        <div className="sidebar-footer">
          <span className="live-dot" /> Controlled operations
          <p>
            Human decisions.
            <br />
            Traceable outcomes.
          </p>
        </div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <div>
            <span className="eyebrow">TENANT WORKSPACE</span>
            <strong>
              {context.tenantId.slice(0, 8)}…{" "}
              <span className="role">{context.role}</span>
            </strong>
          </div>
          <div className="actions">
            <button onClick={onSwitch}>Switch tenant</button>
            <button onClick={onLogout}>Sign out</button>
          </div>
        </header>
        <main id="workspace-main" tabIndex={-1} key={page}>
          {page === "dashboard" ? (
            <Dashboard api={api} context={context} navigate={setPage} />
          ) : page === "agents" ? (
            <Agents api={api} manager={manager} />
          ) : page === "knowledge" ? (
            <Knowledge api={api} manager={manager} />
          ) : page === "search" ? (
            <Search api={api} />
          ) : page === "executions" ? (
            <Executions
              api={api}
              acknowledged={acknowledged}
              onAcknowledge={onAcknowledge}
            />
          ) : page === "approvals" ? (
            <Approvals
              api={api}
              context={context}
              onTasks={() => setPage("tasks")}
            />
          ) : (
            <Tasks api={api} />
          )}
        </main>
        <footer className="workspace-footer">
          Enterprise AI Agent Platform{" "}
          <span>Development console · tenant-scoped access</span>
        </footer>
      </div>
    </div>
  );
}
