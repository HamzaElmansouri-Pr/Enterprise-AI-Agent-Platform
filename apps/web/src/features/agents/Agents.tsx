import { TableScroll } from "../../components/ui";
import { useCallback, useState } from "react";
import type { Agent } from "../../api/contracts";
import type { AgentInput, TenantApi } from "../../api/tenant";
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
export function Agents({ api, manager }: { api: TenantApi; manager: boolean }) {
  const [page, setPage] = useState(0),
    [status, setStatus] = useState(""),
    [selected, setSelected] = useState<string | null>(null),
    [creating, setCreating] = useState(false);
  const remote = useRemote(
    useCallback(
      (signal: AbortSignal) => api.agents(page, status, signal),
      [api, page, status],
    ),
  );
  if (creating)
    return (
      <AgentEditor
        api={api}
        onBack={() => {
          setCreating(false);
          remote.refresh();
        }}
      />
    );
  if (selected)
    return (
      <AgentDetail
        key={selected}
        api={api}
        id={selected}
        manager={manager}
        onBack={() => {
          setSelected(null);
          remote.refresh();
        }}
      />
    );
  return (
    <>
      <Heading
        title="Agent configurations"
        description="Manage the instructions and knowledge context your agents use."
        action={
          manager && (
            <button className="primary" onClick={() => setCreating(true)}>
              Create agent
            </button>
          )
        }
      />
      <div className="toolbar">
        <Field label="Status filter">
          <select
            value={status}
            onChange={(e) => {
              setPage(0);
              setStatus(e.target.value);
            }}
          >
            <option value="">All statuses</option>
            <option>DRAFT</option>
            <option>ARCHIVED</option>
          </select>
        </Field>
        <button onClick={remote.refresh}>Refresh</button>
      </div>
      <ErrorNotice error={remote.error} />
      {remote.loading && <Loading />}
      {remote.data && (
        <>
          <TableScroll>
            <table>
              <thead>
                <tr>
                  <th>Agent</th>
                  <th>Status</th>
                  <th>Updated</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {remote.data.items.map((a) => (
                  <tr key={a.id}>
                    <td>
                      <strong>{a.name}</strong>
                      <small>{a.description || "No description"}</small>
                    </td>
                    <td>
                      <Status value={a.status} />
                    </td>
                    <td>
                      <When value={a.updatedAt} />
                    </td>
                    <td>
                      <button onClick={() => setSelected(a.id)}>
                        View agent
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </TableScroll>
          {!remote.data.items.length && (
            <Empty>
              No agents on this page. Managers can create a DRAFT configuration.
            </Empty>
          )}
          <Pager page={page} hasNext={remote.data.hasNext} onChange={setPage} />
        </>
      )}
    </>
  );
}
function AgentDetail({
  api,
  id,
  manager,
  onBack,
}: {
  api: TenantApi;
  id: string;
  manager: boolean;
  onBack: () => void;
}) {
  const remote = useRemote(
    useCallback((signal: AbortSignal) => api.agent(id, { signal }), [api, id]),
  );
  const [editing, setEditing] = useState(false),
    [confirm, setConfirm] = useState(false);
  const action = useAction();
  const agent = remote.data;
  if (editing && agent)
    return (
      <AgentEditor
        api={api}
        agent={agent}
        onBack={() => {
          setEditing(false);
          remote.refresh();
        }}
      />
    );
  return (
    <>
      <button onClick={onBack}>← All agents</button>
      <ErrorNotice error={remote.error} />
      {remote.loading && <Loading />}
      {agent && (
        <>
          <Heading
            title={agent.name}
            description="Configuration details"
            action={<Status value={agent.status} />}
          />
          <section className="card">
            <Id value={agent.id} />
            <h2>Description</h2>
            <p className="preserve">{agent.description || "No description"}</p>
            <h2>System instructions</h2>
            <pre>{agent.systemInstructions}</pre>
            {manager && agent.status === "DRAFT" && (
              <div className="actions">
                <button onClick={() => setEditing(true)}>
                  Edit configuration
                </button>
                <button className="danger" onClick={() => setConfirm(true)}>
                  Archive agent
                </button>
              </div>
            )}
            {agent.status === "ARCHIVED" && (
              <p>This archived configuration is read-only.</p>
            )}
          </section>
          {confirm && (
            <Confirm
              title="Archive this agent?"
              busy={action.busy}
              onCancel={() => setConfirm(false)}
              onConfirm={() =>
                void action.act(
                  (signal) => api.archive(id, signal),
                  () => {
                    setConfirm(false);
                    remote.refresh();
                  },
                )
              }
              confirm="Archive agent"
            >
              <p>
                {agent.name} will become read-only. This is terminal; knowledge
                search and new executions will be unavailable.
              </p>
              <ErrorNotice error={action.error} />
            </Confirm>
          )}
        </>
      )}
    </>
  );
}
function AgentEditor({
  api,
  agent,
  onBack,
}: {
  api: TenantApi;
  agent?: Agent;
  onBack: () => void;
}) {
  const [input, setInput] = useState<AgentInput>({
    name: agent?.name ?? "",
    description: agent?.description ?? "",
    systemInstructions: agent?.systemInstructions ?? "",
  });
  const action = useAction();
  function field(key: keyof AgentInput, value: string) {
    setInput({ ...input, [key]: value });
  }
  return (
    <>
      <button onClick={onBack}>← Back</button>
      <Heading
        title={agent ? "Edit configuration" : "Create a DRAFT agent"}
        description="Instructions are configuration, never authorization."
      />
      <form
        className="card form-width"
        onSubmit={(e) => {
          e.preventDefault();
          void action.act(
            (signal) =>
              agent
                ? api.agent(agent.id, { method: "PUT", json: input, signal })
                : api.createAgent(input, signal),
            onBack,
          );
        }}
      >
        <Field label="Name" hint="Required, up to 120 characters.">
          <input
            required
            maxLength={120}
            pattern=".*\S.*"
            value={input.name}
            onChange={(e) => field("name", e.target.value)}
          />
        </Field>
        <Field label="Description">
          <textarea
            maxLength={1000}
            value={input.description}
            onChange={(e) => field("description", e.target.value)}
          />
        </Field>
        <Field
          label="System instructions"
          hint="Required, up to 10,000 characters."
        >
          <textarea
            required
            maxLength={10000}
            rows={8}
            value={input.systemInstructions}
            onChange={(e) => field("systemInstructions", e.target.value)}
          />
        </Field>
        <ErrorNotice error={action.error} />
        <button
          className="primary"
          disabled={
            action.busy ||
            !input.name.trim() ||
            !input.systemInstructions.trim()
          }
        >
          {action.busy ? "Saving…" : "Save configuration"}
        </button>
      </form>
    </>
  );
}
