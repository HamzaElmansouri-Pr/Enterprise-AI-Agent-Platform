import { FailureNotice } from "../../components/FailureNotice";
import { safeFailure } from "../../api/failures";
import { validateFile } from "./validation";
import { useCallback, useEffect, useRef, useState } from "react";
import type { KnowledgeDocument, Match } from "../../api/contracts";
import type { TenantApi } from "../../api/tenant";
import { AgentSelect } from "../../components/AgentSelect";
import { useAction, useRemote } from "../../components/hooks";
import {
  Empty,
  ErrorNotice,
  Field,
  Heading,
  Id,
  Loading,
  Pager,
  Status,
} from "../../components/ui";
export function Knowledge({
  api,
  manager,
}: {
  api: TenantApi;
  manager: boolean;
}) {
  const [page, setPage] = useState(0),
    [agent, setAgent] = useState(""),
    [file, setFile] = useState<File | null>(null),
    [fileError, setFileError] = useState<string | null>(null),
    [selected, setSelected] = useState<string | null>(null);
  const remote = useRemote(
    useCallback(
      (signal: AbortSignal) => api.documents(page, signal),
      [api, page],
    ),
  );
  const action = useAction();
  const fileInput = useRef<HTMLInputElement>(null);
  const attempts = useRef(0);
  const { data, refresh } = remote;
  const active =
    !selected &&
    (data?.items.some(
      (d) => d.status === "PENDING" || d.status === "PROCESSING",
    ) ??
      false);
  useEffect(() => {
    if (!active || attempts.current >= 60) return;
    const timer = setTimeout(() => {
      attempts.current++;
      refresh();
    }, 3000);
    return () => clearTimeout(timer);
  }, [active, data, refresh]);
  if (selected)
    return (
      <DocumentDetail
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
        title="Knowledge documents"
        description="Upload institutional knowledge and track durable processing."
      />
      {manager && (
        <form
          className="card form-width"
          onSubmit={(e) => {
            e.preventDefault();
            const error = validateFile(file);
            setFileError(error);
            if (error || !file) return;
            void action.act(
              (signal) => api.upload(file, agent, signal),
              () => {
                attempts.current = 0;
                remote.refresh();
                action.setNotice(
                  "Document accepted. Processing is pending. No automatic retry will occur.",
                );
                setFile(null);
                if (fileInput.current) fileInput.current.value = "";
              },
            );
          }}
        >
          <fieldset disabled={action.busy}>
            <h2>Upload a document</h2>
            <p>
              PDF or UTF-8 TXT · maximum 2 MiB · up to 25 PDF pages. Existing
              tenant and parsing limits apply.
            </p>
            <AgentSelect api={api} value={agent} onChange={setAgent} />
            <Field label="Document file">
              <input
                ref={fileInput}
                type="file"
                accept=".pdf,.txt,application/pdf,text/plain"
                aria-invalid={!!fileError}
                aria-describedby={fileError ? "file-error" : undefined}
                onChange={(e) => {
                  setFile(e.target.files?.[0] ?? null);
                  setFileError(null);
                }}
              />
            </Field>
            {fileError && (
              <p id="file-error" role="alert">
                {fileError}
              </p>
            )}
            <ErrorNotice error={action.error} />
            {action.notice && <p role="status">{action.notice}</p>}
            <button className="primary" disabled={action.busy || !agent}>
              {action.busy ? "Uploading…" : "Upload document"}
            </button>
          </fieldset>
        </form>
      )}
      <div className="toolbar">
        <h2>Document library</h2>
        <button
          onClick={() => {
            attempts.current = 0;
            remote.refresh();
          }}
        >
          Refresh documents
        </button>
      </div>
      <p className="muted">
        Pending work refreshes every 3 seconds, up to 60 times while this view
        is open. Use Refresh to check again.
      </p>
      <ErrorNotice error={remote.error} />
      {remote.loading && <Loading />}
      {remote.data && (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Filename</th>
                  <th>Processing</th>
                  <th>Chunks</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {remote.data.items.map((d) => (
                  <tr key={d.id}>
                    <td>
                      {d.filename}
                      <small>{(d.byteSize / 1024).toFixed(1)} KiB</small>
                    </td>
                    <td>
                      <Status value={d.status} />
                      {d.failureCode && (
                        <small>{safeFailure(d.failureCode)}</small>
                      )}
                    </td>
                    <td>{d.chunkCount}</td>
                    <td>
                      <button onClick={() => setSelected(d.id)}>
                        View document
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!remote.data.items.length && (
            <Empty>No documents on this page.</Empty>
          )}
          <Pager page={page} hasNext={remote.data.hasNext} onChange={setPage} />
        </>
      )}
    </>
  );
}
function DocumentDetail({
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
    useCallback((signal: AbortSignal) => api.document(id, signal), [api, id]),
  );
  const [agent, setAgent] = useState("");
  const action = useAction();
  const names = useRemote(
    useCallback((signal: AbortSignal) => api.agents(0, "", signal), [api]),
  );
  const d = remote.data;
  const attempts = useRef(0),
    refresh = remote.refresh;
  useEffect(() => {
    if (
      !d ||
      !["PENDING", "PROCESSING"].includes(d.status) ||
      attempts.current >= 60
    )
      return;
    const timer = setTimeout(() => {
      attempts.current++;
      refresh();
    }, 3000);
    return () => clearTimeout(timer);
  }, [d, refresh]);
  function updated() {
    remote.refresh();
    action.setNotice("Document updated.");
  }
  return (
    <>
      <button onClick={onBack}>← Document library</button>
      <ErrorNotice error={remote.error} />
      {remote.loading && <Loading />}
      {d && (
        <>
          <Heading
            title={d.filename}
            description="Processing and agent associations"
            action={<Status value={d.status} />}
          />
          <section className="card">
            <Id value={d.id} />
            <p>Chunks: {d.chunkCount}</p>
            {d.failureCode && <FailureNotice code={d.failureCode} />}
            <button onClick={remote.refresh}>Refresh status</button>
            {manager && d.status === "FAILED" && (
              <button
                disabled={action.busy}
                onClick={() =>
                  void action.act((signal) => api.retry(id, signal), updated)
                }
              >
                Retry processing
              </button>
            )}
            <h2>Associated agents</h2>
            <p>
              Only READY documents associated with an agent can support its
              answers.
            </p>
            <ErrorNotice error={names.error} />
            {d.agentIds.length ? (
              d.agentIds.map((a) => (
                <div className="association" key={a}>
                  <div>
                    <strong>
                      {names.data?.items.find((item) => item.id === a)?.name ??
                        "Associated agent"}
                    </strong>
                    <small>
                      Agent ID: <Id value={a} />
                    </small>
                  </div>
                  {manager && (
                    <button
                      disabled={action.busy}
                      onClick={() =>
                        void action.act(
                          (signal) => api.association(id, a, true, signal),
                          updated,
                        )
                      }
                    >
                      Remove association
                    </button>
                  )}
                </div>
              ))
            ) : (
              <Empty>No associated agents.</Empty>
            )}
            {d.agentIds.some(
              (a) => !names.data?.items.some((item) => item.id === a),
            ) && (
              <p>
                Names are shown for the first page of accessible agents. Use the
                Agents list and support ID to identify other associations.
              </p>
            )}
            {d.hasMoreAgentIds && (
              <p>Only the first 100 associations are returned by this API.</p>
            )}
            {manager && (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void action.act(
                    (signal) => api.association(id, agent, false, signal),
                    updated,
                  );
                }}
              >
                <fieldset disabled={action.busy}>
                  <AgentSelect api={api} value={agent} onChange={setAgent} />
                  <button disabled={action.busy || !agent}>
                    Associate agent
                  </button>
                </fieldset>
              </form>
            )}
            <ErrorNotice error={action.error} />
            {action.notice && <p role="status">{action.notice}</p>}
          </section>
        </>
      )}
    </>
  );
}
export function Search({ api }: { api: TenantApi }) {
  const [agent, setAgent] = useState(""),
    [query, setQuery] = useState(""),
    [matches, setMatches] = useState<Match[] | null>(null);
  const action = useAction();
  return (
    <>
      <Heading
        title="Knowledge search"
        description="Find relevant passages in the selected agent’s READY documents."
      />
      <form
        className="card form-width"
        onSubmit={(e) => {
          e.preventDefault();
          setMatches(null);
          void action.act(
            (signal) => api.search(agent, query, signal),
            (r) => setMatches(r.results),
          );
        }}
      >
        <fieldset disabled={action.busy}>
          <AgentSelect
            api={api}
            value={agent}
            onChange={(id) => {
              setAgent(id);
              setMatches(null);
            }}
          />
          <Field label="Search query" hint="Up to 1,000 characters.">
            <textarea
              required
              maxLength={1000}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </Field>
          <button
            className="primary"
            disabled={action.busy || !agent || !query.trim()}
          >
            {action.busy ? "Searching…" : "Search knowledge"}
          </button>
        </fieldset>
      </form>
      <ErrorNotice error={action.error} />
      {matches && (
        <section aria-live="polite">
          <h2>Relevant passages</h2>
          <p>
            Cosine similarity measures semantic closeness, not factual
            confidence.
          </p>
          {!matches.length && (
            <Empty>No matching READY passages were returned.</Empty>
          )}
          {matches.map((m) => (
            <article className="card" key={m.chunkId}>
              <h3>{m.filename}</h3>
              <p className="preserve">{m.text}</p>
              <p>
                Similarity: {m.score.toFixed(4)}
                {m.sourcePage !== null && ` · Page ${m.sourcePage}`}
              </p>
              <Id value={m.documentId} />
            </article>
          ))}
        </section>
      )}
    </>
  );
}
export function DocumentSummary({ document }: { document: KnowledgeDocument }) {
  return (
    <>
      <strong>{document.filename}</strong>
      <Status value={document.status} />
      <small>{document.chunkCount} chunks</small>
    </>
  );
}
