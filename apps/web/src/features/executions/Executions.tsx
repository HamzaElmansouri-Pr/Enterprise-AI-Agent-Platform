import { FailureNotice } from "../../components/FailureNotice";
import { useState } from "react";
import { ApiError } from "../../api/client";
import { isUuid } from "../../api/contracts";
import type { Run } from "../../api/contracts";
import type { TenantApi } from "../../api/tenant";
import { AgentSelect } from "../../components/AgentSelect";
import { useAction } from "../../components/hooks";
import {
  Confirm,
  CostNotice,
  ErrorNotice,
  Field,
  Heading,
  Id,
  Status,
} from "../../components/ui";
export function Executions({
  api,
  acknowledged,
  onAcknowledge,
}: {
  api: TenantApi;
  acknowledged: boolean;
  onAcknowledge: () => void;
}) {
  const [agent, setAgent] = useState(""),
    [question, setQuestion] = useState(""),
    [submitted, setSubmitted] = useState(""),
    [run, setRun] = useState<Run | null>(null),
    [runId, setRunId] = useState(""),
    [confirm, setConfirm] = useState(false);
  const action = useAction();
  function start() {
    setConfirm(false);
    onAcknowledge();
    setRun(null);
    setSubmitted(question);
    setRunId("");
    void action.act(
      (signal) => api.run(agent, question, signal),
      (r) => {
        setRun(r);
        setRunId(r.runId);
      },
    );
  }
  const failedId = action.error instanceof ApiError ? action.error.runId : null;
  return (
    <>
      <Heading
        title="Ask an agent"
        description="A single-turn answer grounded in the selected agent’s knowledge."
      />
      <form
        className="card form-width"
        onSubmit={(e) => {
          e.preventDefault();
          if (acknowledged) start();
          else setConfirm(true);
        }}
      >
        <fieldset disabled={action.busy}>
          <AgentSelect
            api={api}
            value={agent}
            onChange={(id) => {
              setAgent(id);
              setRun(null);
              setRunId("");
              setSubmitted("");
            }}
          />
          <Field
            label="Question"
            hint="Up to 1,000 characters. No conversation memory is retained by this interface."
          >
            <textarea
              required
              maxLength={1000}
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
            />
          </Field>
          <CostNotice />
          <button
            className="primary"
            disabled={action.busy || !agent || !question.trim()}
          >
            {action.busy ? "Running…" : "Run question"}
          </button>
        </fieldset>
      </form>
      <ErrorNotice error={action.error} />
      {failedId && (
        <p>
          A durable run is available: <Id value={failedId} />
          <button
            disabled={action.busy}
            onClick={() =>
              void action.act(
                (signal) => api.runStatus(agent, failedId, signal),
                (r) => {
                  setRun(r);
                  setRunId(r.runId);
                },
              )
            }
          >
            Retrieve outcome
          </button>
        </p>
      )}
      {confirm && (
        <Confirm
          title="Run a hosted execution?"
          confirm="Run question"
          busy={action.busy}
          onCancel={() => setConfirm(false)}
          onConfirm={start}
        >
          <CostNotice />
          <p>
            This sends the question and authorized context to the configured
            provider.
          </p>
        </Confirm>
      )}
      {submitted && (
        <section className="card">
          <h2>Submitted question</h2>
          <p className="preserve">{submitted}</p>
        </section>
      )}
      {run && <RunResult run={run} />}
      <form
        className="card form-width"
        onSubmit={(e) => {
          e.preventDefault();
          // The status DTO has no question; do not pair another run with an old question.
          setSubmitted("");
          setRun(null);
          void action.act(
            (signal) => api.runStatus(agent, runId, signal),
            setRun,
          );
        }}
      >
        <h2>Retrieve a run</h2>
        <p>Check an existing outcome without starting another paid attempt.</p>
        <Field label="Run UUID">
          <input
            required
            value={runId}
            onChange={(e) => setRunId(e.target.value)}
          />
        </Field>
        <button disabled={action.busy || !agent || !isUuid(runId)}>
          Retrieve run
        </button>
      </form>
    </>
  );
}
export function RunResult({ run }: { run: Run }) {
  return (
    <section className="card" aria-live="polite">
      <h2>
        Execution result <Status value={run.status} />
      </h2>
      <Id value={run.runId} />
      {run.answer && <p className="preserve">{run.answer}</p>}
      {run.failureCode && <FailureNotice code={run.failureCode} />}
      <dl className="facts">
        <div>
          <dt>Model</dt>
          <dd>{run.modelId ?? "Unavailable"}</dd>
        </div>
        <div>
          <dt>Input tokens</dt>
          <dd>{run.usage.inputTokens ?? "Unavailable"}</dd>
        </div>
        <div>
          <dt>Output tokens</dt>
          <dd>{run.usage.outputTokens ?? "Unavailable"}</dd>
        </div>
        <div>
          <dt>Duration</dt>
          <dd>
            {run.durationMs === null ? "Unavailable" : `${run.durationMs} ms`}
          </dd>
        </div>
      </dl>
      <h3>Source citations</h3>
      {!run.citations.length && <p>No citations available.</p>}
      {run.citations.map((c) => (
        <div key={c.chunkId}>
          <strong>{c.filename}</strong>
          {c.sourcePage !== null && ` · Page ${c.sourcePage}`}
          <Id value={c.documentId} />
        </div>
      ))}
    </section>
  );
}
