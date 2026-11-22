import { useCallback } from "react";
import type { TenantContext } from "../../api/contracts";
import type { TenantApi } from "../../api/tenant";
import { useRemote } from "../../components/hooks";
import {
  Empty,
  ErrorNotice,
  Heading,
  Id,
  Loading,
  Status,
} from "../../components/ui";
import { DocumentSummary } from "../knowledge/Knowledge";
export function Dashboard({
  api,
  context,
  navigate,
}: {
  api: TenantApi;
  context: TenantContext;
  navigate: (page: string) => void;
}) {
  const remote = useRemote(
    useCallback(
      async (signal: AbortSignal) => {
        const [agents, documents, proposals] = await Promise.all([
          api.agents(0, "", signal),
          api.documents(0, signal),
          api.proposals(0, signal),
        ]);
        return { agents, documents, proposals };
      },
      [api],
    ),
  );
  return (
    <>
      <Heading
        title="Workspace overview"
        description="Your tenant’s agents, knowledge and pending operational work."
      />
      <section className="card">
        <p className="eyebrow">CURRENT TENANT</p>
        <Id value={context.tenantId} />
        <p>
          Current role: <strong>{context.role}</strong>
        </p>
        <small>Permissions are checked by Spring for every request.</small>
      </section>
      <ErrorNotice error={remote.error} />
      {remote.loading && <Loading />}
      {remote.data && (
        <>
          <div className="summary-grid">
            <section className="card">
              <p className="eyebrow">ACCESSIBLE AGENTS</p>
              <strong className="metric">
                {remote.data.agents.items.length}
                {remote.data.agents.hasNext ? " +" : ""}
              </strong>
              <p>
                {remote.data.agents.hasNext
                  ? "Shown on the first page; more available."
                  : "Configurations returned by the current list."}
              </p>
              <button onClick={() => navigate("agents")}>
                Manage agents →
              </button>
            </section>
            <section className="card">
              <p className="eyebrow">HUMAN CONTROL</p>
              <h2>Review before action</h2>
              <p>
                Proposals stay inert until approved by another manager and
                explicitly executed.
              </p>
              <button onClick={() => navigate("approvals")}>
                Review proposals →
              </button>
            </section>
          </div>
          <div className="dashboard-grid">
            <section className="card">
              <h2>Recent configurations</h2>
              {!remote.data.agents.items.length && (
                <Empty>No agents yet.</Empty>
              )}
              {remote.data.agents.items.slice(0, 5).map((a) => (
                <div className="list-row" key={a.id}>
                  <strong>{a.name}</strong>
                  <Status value={a.status} />
                </div>
              ))}
            </section>
            <section className="card">
              <h2>Recent documents</h2>
              {!remote.data.documents.items.length && (
                <Empty>No documents yet.</Empty>
              )}
              {remote.data.documents.items.slice(0, 5).map((d) => (
                <div className="list-row" key={d.id}>
                  <DocumentSummary document={d} />
                </div>
              ))}
              <button onClick={() => navigate("knowledge")}>
                Open library →
              </button>
            </section>
            <section className="card">
              <h2>Recent visible proposals</h2>
              {!remote.data.proposals.items.length && (
                <Empty>No visible proposals yet.</Empty>
              )}
              {remote.data.proposals.items.slice(0, 5).map((p) => (
                <div className="list-row" key={p.id}>
                  <strong>{p.arguments.title}</strong>
                  <Status value={p.status} />
                </div>
              ))}
            </section>
          </div>
        </>
      )}
    </>
  );
}
