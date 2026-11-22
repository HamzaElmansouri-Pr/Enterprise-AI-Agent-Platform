import { safeFailure } from "../../api/failures";
import { useCallback, useState } from "react";
import type { Proposal, TenantContext } from "../../api/contracts";
import type { ManualInput, TenantApi } from "../../api/tenant";
import { AgentSelect } from "../../components/AgentSelect";
import { useAction, useRemote } from "../../components/hooks";
import {
  Confirm,
  CostNotice,
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
export function Approvals({
  api,
  context,
  onTasks,
}: {
  api: TenantApi;
  context: TenantContext;
  onTasks: () => void;
}) {
  const [page, setPage] = useState(0),
    [selected, setSelected] = useState<string | null>(null),
    [creating, setCreating] = useState(false);
  const remote = useRemote(
    useCallback(
      (signal: AbortSignal) => api.proposals(page, signal),
      [api, page],
    ),
  );
  const manager = context.role !== "MEMBER";
  if (selected)
    return (
      <ProposalDetail
        key={selected}
        api={api}
        id={selected}
        context={context}
        onBack={() => {
          setSelected(null);
          remote.refresh();
        }}
        onTasks={onTasks}
      />
    );
  if (creating)
    return (
      <ProposalCreate
        api={api}
        manager={manager}
        onBack={() => setCreating(false)}
        onCreated={(id) => {
          setCreating(false);
          setSelected(id);
        }}
      />
    );
  return (
    <>
      <Heading
        title="Tool proposals"
        description={
          manager
            ? "Review exact proposals before authorizing a real business action."
            : "Your proposals. Only authorized managers can approve and execute."
        }
        action={
          <button className="primary" onClick={() => setCreating(true)}>
            New proposal
          </button>
        }
      />
      <div className="toolbar">
        <p>Creation and approval do not create tasks.</p>
        <button onClick={remote.refresh}>Refresh proposals</button>
      </div>
      <ErrorNotice error={remote.error} />
      {remote.loading && <Loading />}
      {remote.data && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Proposal</th>
                  <th>Status</th>
                  <th>Creator / agent</th>
                  <th>Decision / result</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {remote.data.items.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <strong>{p.arguments.title}</strong>
                      <small>{p.toolName}</small>
                      <small>
                        <When value={p.createdAt} />
                      </small>
                    </td>
                    <td>
                      <Status value={p.status} />
                    </td>
                    <td>
                      <small>
                        Creator <Id value={p.createdBy} />
                      </small>
                      <small>
                        Agent <Id value={p.agentId} />
                      </small>
                    </td>
                    <td>
                      {p.decision ? (
                        <>
                          <Status value={p.decision.decision} />
                          <small>
                            Reviewer <Id value={p.decision.approverId} />
                          </small>
                        </>
                      ) : (
                        "No decision"
                      )}
                      {p.taskId && (
                        <small>
                          Task <Id value={p.taskId} />
                        </small>
                      )}
                    </td>
                    <td>
                      <button onClick={() => setSelected(p.id)}>
                        Review proposal
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!remote.data.items.length && (
            <Empty>No visible proposals on this page.</Empty>
          )}
          <Pager page={page} hasNext={remote.data.hasNext} onChange={setPage} />
        </>
      )}
    </>
  );
}
function ProposalCreate({
  api,
  manager,
  onBack,
  onCreated,
}: {
  api: TenantApi;
  manager: boolean;
  onBack: () => void;
  onCreated: (id: string) => void;
}) {
  const [agent, setAgent] = useState(""),
    [mode, setMode] = useState(manager ? "manual" : "ai"),
    [intent, setIntent] = useState(""),
    [confirm, setConfirm] = useState(false);
  const [input, setInput] = useState<ManualInput>({
    toolName: "admissions.create_followup_task",
    arguments: { title: "", description: "", priority: "NORMAL" },
    rationale: "",
  });
  const action = useAction();
  function generate() {
    setConfirm(false);
    void action.act(
      (signal) => api.generate(agent, intent, signal),
      (p) => onCreated(p.id),
    );
  }
  return (
    <>
      <button onClick={onBack}>← All proposals</button>
      <Heading
        title="Create a proposal"
        description="Submitting creates an unapproved proposal, never a task."
      />
      <form
        className="card form-width"
        onSubmit={(e) => {
          e.preventDefault();
          if (mode === "ai") setConfirm(true);
          else
            void action.act(
              (signal) => api.submit(agent, input, signal),
              (p) => onCreated(p.id),
            );
        }}
      >
        <fieldset disabled={action.busy}>
          {manager && (
            <Field label="Proposal source">
              <select value={mode} onChange={(e) => setMode(e.target.value)}>
                <option value="manual">Manual · no model call</option>
                <option value="ai">AI generation · optional paid call</option>
              </select>
            </Field>
          )}
          <AgentSelect api={api} value={agent} onChange={setAgent} />
          {mode === "manual" ? (
            <>
              <p>Tool: admissions.create_followup_task · version 1</p>
              <Field
                label="Task title"
                hint="Required, up to 120 characters. No URLs or unsafe controls."
              >
                <input
                  required
                  maxLength={120}
                  value={input.arguments.title}
                  onChange={(e) =>
                    setInput({
                      ...input,
                      arguments: { ...input.arguments, title: e.target.value },
                    })
                  }
                />
              </Field>
              <Field label="Task description">
                <textarea
                  required
                  maxLength={1000}
                  value={input.arguments.description}
                  onChange={(e) =>
                    setInput({
                      ...input,
                      arguments: {
                        ...input.arguments,
                        description: e.target.value,
                      },
                    })
                  }
                />
              </Field>
              <Field label="Priority">
                <select
                  value={input.arguments.priority}
                  onChange={(e) =>
                    setInput({
                      ...input,
                      arguments: {
                        ...input.arguments,
                        priority: e.target
                          .value as ManualInput["arguments"]["priority"],
                      },
                    })
                  }
                >
                  <option>LOW</option>
                  <option>NORMAL</option>
                  <option>HIGH</option>
                </select>
              </Field>
              <Field label="Rationale" hint="Required, up to 500 characters.">
                <textarea
                  required
                  maxLength={500}
                  value={input.rationale}
                  onChange={(e) =>
                    setInput({ ...input, rationale: e.target.value })
                  }
                />
              </Field>
            </>
          ) : (
            <>
              <CostNotice />
              <p>
                AI generation only produces a PENDING_APPROVAL proposal. It
                cannot approve or execute.
              </p>
              <Field label="Intent">
                <textarea
                  required
                  maxLength={1000}
                  value={intent}
                  onChange={(e) => setIntent(e.target.value)}
                />
              </Field>
            </>
          )}
          <ErrorNotice error={action.error} />
          <button className="primary" disabled={!agent || action.busy}>
            {action.busy
              ? "Submitting…"
              : mode === "manual"
                ? "Submit unapproved proposal"
                : "Generate unapproved proposal"}
          </button>
        </fieldset>
      </form>
      {confirm && (
        <Confirm
          title="Generate a proposal with AI?"
          busy={action.busy}
          onCancel={() => setConfirm(false)}
          onConfirm={generate}
          confirm="Generate proposal"
        >
          <CostNotice />
          <p>
            No business action will execute. A different authorized human must
            approve the resulting proposal.
          </p>
        </Confirm>
      )}
    </>
  );
}
export function ProposalPayload({ proposal: p }: { proposal: Proposal }) {
  return (
    <dl className="payload">
      <div>
        <dt>Proposal ID</dt>
        <dd>
          <Id value={p.id} />
        </dd>
      </div>
      <div>
        <dt>Tool / version</dt>
        <dd>
          {p.toolName} / {p.toolVersion}
        </dd>
      </div>
      <div>
        <dt>Creator</dt>
        <dd>
          <Id value={p.createdBy} />
        </dd>
      </div>
      <div>
        <dt>Title</dt>
        <dd className="preserve">{p.arguments.title}</dd>
      </div>
      <div>
        <dt>Description</dt>
        <dd className="preserve">{p.arguments.description}</dd>
      </div>
      <div>
        <dt>Priority</dt>
        <dd>{p.arguments.priority}</dd>
      </div>
      <div>
        <dt>Rationale</dt>
        <dd className="preserve">{p.rationale}</dd>
      </div>
      <div>
        <dt>Payload digest</dt>
        <dd>
          <Id value={p.payloadDigest} />
        </dd>
      </div>
    </dl>
  );
}
export function ProposalDetail({
  api,
  id,
  context,
  onBack,
  onTasks,
}: {
  api: TenantApi;
  id: string;
  context: TenantContext;
  onBack: () => void;
  onTasks: () => void;
}) {
  const remote = useRemote(
    useCallback((signal: AbortSignal) => api.proposal(id, signal), [api, id]),
  );
  const action = useAction();
  const [confirmation, setConfirmation] = useState<
      "approve" | "reject" | "execute" | null
    >(null),
    [reason, setReason] = useState("");
  const p = remote.data,
    manager = context.role !== "MEMBER";
  function finish() {
    setConfirmation(null);
    setReason("");
    remote.refresh();
    action.setNotice("Outcome saved. Review the current status below.");
  }
  function confirm() {
    if (
      !p ||
      (confirmation === "reject" && (!reason.trim() || reason.length > 500))
    )
      return;
    void action.act(
      (signal) =>
        confirmation === "execute"
          ? api.execute(p.id, signal)
          : api.decide(
              p.id,
              p.payloadDigest,
              confirmation === "reject" ? reason : null,
              signal,
            ),
      finish,
    );
  }
  return (
    <>
      <button onClick={onBack}>← All proposals</button>
      <ErrorNotice error={remote.error} />
      {remote.loading && <Loading />}
      {p && (
        <>
          <Heading
            title="Review proposal"
            description="Immutable arguments. Human decisions and execution are separate."
            action={<Status value={p.status} />}
          />
          <section className="card">
            <ProposalPayload proposal={p} />
            <div className="actions">
              <button disabled={action.busy} onClick={remote.refresh}>
                Refresh proposal
              </button>
              {manager && p.status === "PENDING_APPROVAL" && (
                <>
                  <button
                    className="primary"
                    disabled={action.busy || p.createdBy === context.userId}
                    onClick={() => setConfirmation("approve")}
                  >
                    Approve proposal
                  </button>
                  <button
                    className="danger"
                    disabled={action.busy}
                    onClick={() => setConfirmation("reject")}
                  >
                    Reject proposal
                  </button>
                </>
              )}
              {manager && p.status === "APPROVED" && (
                <button
                  className="primary"
                  disabled={action.busy}
                  onClick={() => setConfirmation("execute")}
                >
                  Execute approved action
                </button>
              )}
            </div>
            {p.status === "PENDING_APPROVAL" &&
              p.createdBy === context.userId && (
                <p>
                  You created this proposal. A different authorized manager must
                  approve it.
                </p>
              )}
            {p.status === "APPROVED" && (
              <p>
                Approved only. No task exists until a manager explicitly
                executes this action.
              </p>
            )}
            {p.decision && (
              <div>
                <h2>Decision evidence</h2>
                <p>
                  {p.decision.decision} by <Id value={p.decision.approverId} />
                </p>
                <p>
                  <When value={p.decision.decidedAt} />
                </p>
                {p.decision.reason && (
                  <p className="preserve">{p.decision.reason}</p>
                )}
              </div>
            )}
            {p.executedBy && (
              <p>
                Executed by <Id value={p.executedBy} />
              </p>
            )}
            {p.failureCode && <p>Failure: {safeFailure(p.failureCode)}</p>}
            {p.taskId && (
              <div role="status">
                <h2>Created task</h2>
                <Id value={p.taskId} />
                <button onClick={onTasks}>View follow-up tasks</button>
              </div>
            )}
            <ErrorNotice error={action.error} />
            {action.notice && <p role="status">{action.notice}</p>}
          </section>
          {confirmation && (
            <Confirm
              disabled={confirmation === "reject" && !reason.trim()}
              title={
                confirmation === "approve"
                  ? "Approve these exact arguments?"
                  : confirmation === "reject"
                    ? "Reject this proposal permanently?"
                    : "Create a real follow-up task?"
              }
              confirm={
                confirmation === "approve"
                  ? "Approve only"
                  : confirmation === "reject"
                    ? "Reject permanently"
                    : "Execute and create task"
              }
              busy={action.busy}
              onCancel={() => setConfirmation(null)}
              onConfirm={confirm}
            >
              <ProposalPayload proposal={p} />
              {confirmation === "approve" && (
                <p>Approval binds this digest. It will not execute the tool.</p>
              )}
              {confirmation === "execute" && (
                <p>
                  This separately executes {p.toolName} and creates one real
                  OPEN task in this tenant using the approved arguments above.
                </p>
              )}
              {confirmation === "reject" && (
                <Field
                  label="Rejection reason"
                  hint="Required, up to 500 characters. Rejection is terminal."
                >
                  <textarea
                    required
                    maxLength={500}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    onInput={(e) =>
                      e.currentTarget.setCustomValidity(
                        e.currentTarget.value.trim() ? "" : "Enter a reason.",
                      )
                    }
                  />
                </Field>
              )}
              <ErrorNotice error={action.error} />
              {confirmation === "reject" && !reason.trim() && (
                <p role="status">Enter a reason before confirming.</p>
              )}
            </Confirm>
          )}
        </>
      )}
    </>
  );
}
