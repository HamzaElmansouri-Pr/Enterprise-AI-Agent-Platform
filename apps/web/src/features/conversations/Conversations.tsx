import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { TenantApi } from "../../api/tenant";
import { ConversationApi } from "../../api/conversations";
import type { Message } from "../../api/conversations";
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
import { ApiError } from "../../api/client";
import { RunResult } from "../executions/Executions";
export function Conversations({
  api,
  acknowledged,
  onAcknowledge,
  pending,
}: {
  api: TenantApi;
  acknowledged: boolean;
  onAcknowledge: () => void;
  pending: Map<string, string>;
}) {
  const [agent, setAgent] = useState(""),
    [selected, setSelected] = useState<string | null>(null);
  const client = useMemo(
    () => (agent ? new ConversationApi(api, agent) : null),
    [api, agent],
  );
  const metadata = useRemote(
    useCallback(
      (signal: AbortSignal) =>
        agent ? api.agent(agent, { signal }) : Promise.resolve(null),
      [api, agent],
    ),
  );
  return (
    <>
      <Heading
        title="Conversations"
        description="Private, persistent conversations grounded in this agent’s authorized knowledge."
      />
      <AgentSelect
        api={api}
        value={agent}
        includeArchived
        onChange={(id) => {
          setAgent(id);
          setSelected(null);
        }}
      />
      <ErrorNotice error={metadata.error} />
      {agent && metadata.loading && <Loading />}
      {client &&
        metadata.data &&
        (selected ? (
          <Chat
            key={selected}
            api={client}
            archived={metadata.data.status === "ARCHIVED"}
            id={selected}
            pending={pending}
            acknowledged={acknowledged}
            onAcknowledge={onAcknowledge}
            onBack={() => setSelected(null)}
          />
        ) : (
          <ConversationList
            key={agent}
            api={client}
            archived={metadata.data.status === "ARCHIVED"}
            agentName={metadata.data.name}
            onOpen={setSelected}
          />
        ))}
    </>
  );
}
function ConversationList({
  api,
  onOpen,
  archived,
  agentName,
}: {
  archived: boolean;
  agentName: string;
  api: ConversationApi;
  onOpen: (id: string) => void;
}) {
  const [page, setPage] = useState(0),
    [title, setTitle] = useState("New conversation");
  const action = useAction();
  const remote = useRemote(
    useCallback((signal: AbortSignal) => api.list(page, signal), [api, page]),
  );
  return (
    <>
      <form
        className="card form-width"
        onSubmit={(e) => {
          e.preventDefault();
          void action.act(
            (signal) => api.create(title, signal),
            (c) => onOpen(c.id),
          );
        }}
      >
        <Field label="Conversation title">
          <input
            required
            maxLength={120}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
        </Field>
        <button disabled={action.busy || archived}>New conversation</button>
        <p>
          At most 20 conversations per user and tenant, including closed
          conversations. Archived agents are read-only.
        </p>
      </form>
      <ErrorNotice error={action.error} />
      <ErrorNotice error={remote.error} />
      {remote.loading && <Loading />}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Title</th>
              <th>Agent</th>
              <th>Status</th>
              <th>Created</th>
              <th>Last activity</th>
              <th>Action</th>
            </tr>
          </thead>
          <tbody>
            {remote.data?.items.map((c) => (
              <tr key={c.id}>
                <td>{c.title}</td>
                <td>
                  {agentName}
                  <Id value={c.agentId} />
                </td>
                <td>
                  <Status value={c.status} />
                </td>
                <td>
                  <When value={c.createdAt} />
                </td>
                <td>
                  <When value={c.updatedAt} />
                </td>
                <td>
                  <button onClick={() => onOpen(c.id)}>
                    Open conversation
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {remote.data && !remote.data.items.length && (
        <Empty>No conversations for this agent.</Empty>
      )}
      {remote.data && (
        <Pager page={page} hasNext={remote.data.hasNext} onChange={setPage} />
      )}
    </>
  );
}
function Chat({
  archived,
  api,
  id,
  pending,
  acknowledged,
  onAcknowledge,
  onBack,
}: {
  api: ConversationApi;
  id: string;
  pending: Map<string, string>;
  archived: boolean;
  acknowledged: boolean;
  onAcknowledge: () => void;
  onBack: () => void;
}) {
  const key = `${api.base}/${id}`;
  const [page, setPage] = useState(0),
    [question, setQuestion] = useState(""),
    [rename, setRename] = useState(""),
    [confirm, setConfirm] = useState<"send" | "close" | null>(null),
    [request, setRequest] = useState<string | null>(
      () => pending.get(key) ?? null,
    ),
    [outcome, setOutcome] = useState<ReturnType<
      typeof import("../../api/conversations").turnContract
    > | null>(null);
  const action = useAction(),
    end = useRef<HTMLDivElement>(null);
  const remote = useRemote(
    useCallback(
      async (signal: AbortSignal) => {
        const [conversation, messages] = await Promise.all([
          api.get(id, signal),
          api.messages(id, page, signal),
        ]);
        return { conversation, messages };
      },
      [api, id, page],
    ),
  );
  useEffect(() => {
    end.current?.scrollIntoView?.({ block: "nearest" });
  }, [remote.data]);
  function resolved(turn: NonNullable<typeof outcome>) {
    setOutcome(turn);
    if (turn.status !== "RUNNING") {
      if (pending.get(key) === turn.requestId) {
        pending.delete(key);
        setRequest(null);
        setQuestion("");
      }
    }
    remote.refresh();
  }
  function retrieve(requestId: string) {
    void action.act((signal) => api.outcome(id, requestId, signal), resolved);
  }
  function send() {
    if (action.busy || request || pending.has(key)) return;
    setConfirm(null);
    onAcknowledge();
    const requestId = crypto.randomUUID();
    pending.set(key, requestId);
    setRequest(requestId);
    setOutcome(null);
    void action.act(async (signal) => {
      try {
        return await api.send(id, requestId, question, signal);
      } catch (error) {
        // A definite rejection has completed the HTTP operation. An ambiguous
        // transport/5xx outcome must retain its ID, even if a later GET is 404.
        if (
          error instanceof ApiError &&
          [400, 403, 404, 409, 429].includes(error.status) &&
          pending.get(key) === requestId
        ) {
          pending.delete(key);
          setRequest(null);
        }
        throw error;
      }
    }, resolved);
  }
  const conversation = remote.data?.conversation;
  return (
    <>
      <button onClick={onBack}>Back to conversations</button>
      <ErrorNotice error={remote.error} />
      {remote.loading && <Loading />}
      {conversation && (
        <>
          <h2>
            {conversation.title} <Status value={conversation.status} />
          </h2>
          <Id value={id} />
          <p>
            Private to your account. Historical citations do not grant access to
            document bytes.
          </p>
          {conversation.status === "OPEN" && (
            <form
              className="actions"
              onSubmit={(e) => {
                e.preventDefault();
                void action.act(
                  (signal) => api.rename(id, rename, signal),
                  () => {
                    setRename("");
                    remote.refresh();
                  },
                );
              }}
            >
              <Field label="New conversation title">
                <input
                  required
                  maxLength={120}
                  value={rename}
                  onChange={(e) => setRename(e.target.value)}
                />
              </Field>
              <button disabled={action.busy}>Rename conversation</button>
              <button
                type="button"
                disabled={action.busy || !!request}
                onClick={() => setConfirm("close")}
              >
                Close conversation
              </button>
            </form>
          )}
          <section
            className="conversation-messages"
            aria-label="Conversation messages"
            aria-live="polite"
          >
            {remote.data?.messages.items.map((m) => (
              <MessageBubble
                key={m.id}
                message={m}
                onRetrieve={() => retrieve(m.requestId)}
                busy={action.busy}
              />
            ))}
            <div ref={end} />
          </section>
          {remote.data && !remote.data.messages.items.length && (
            <Empty>No messages yet.</Empty>
          )}
          {remote.data && (
            <Pager
              page={page}
              hasNext={remote.data.messages.hasNext}
              onChange={setPage}
            />
          )}
          <p>
            Messages are paginated in chronological order. Previous shows
            earlier messages; Next shows later messages.
          </p>
          {request && (
            <section className="card" role="status">
              <p>
                Submitted request: <Id value={request} />. Its outcome may be
                pending or unknown. Retrieve it before sending another message;
                do not resubmit after a timeout.
              </p>
              <button disabled={action.busy} onClick={() => retrieve(request)}>
                Retrieve turn outcome
              </button>
            </section>
          )}
          {outcome && <RunResult run={outcome.result} />}
          {archived && (
            <p>This agent is archived. Existing messages are read-only.</p>
          )}
          {conversation.status === "OPEN" && !archived && (
            <form
              className="card form-width"
              onSubmit={(e) => {
                e.preventDefault();
                if (acknowledged) send();
                else setConfirm("send");
              }}
            >
              <Field
                label="Message"
                hint="Up to 1,000 characters. Up to six prior completed turns are sent to the configured provider."
              >
                <textarea
                  required
                  maxLength={1000}
                  disabled={action.busy || !!request}
                  value={question}
                  onChange={(e) => setQuestion(e.target.value)}
                />
              </Field>
              <CostNotice />
              <button
                className="primary"
                disabled={action.busy || !!request || !question.trim()}
              >
                {action.busy ? "Running…" : "Send message"}
              </button>
            </form>
          )}
        </>
      )}
      <ErrorNotice error={action.error} />
      {action.error && request && (
        <p>
          The request ID is retained for this session. Retrieval is read-only
          and will not start another paid attempt. Keep this ID while inspecting
          service state. A missing status response does not prove an earlier
          request could never arrive.
        </p>
      )}
      {confirm && (
        <Confirm
          title={
            confirm === "send"
              ? "Send to hosted provider?"
              : "Close this conversation?"
          }
          confirm={confirm === "send" ? "Send message" : "Close conversation"}
          busy={action.busy}
          onCancel={() => setConfirm(null)}
          onConfirm={() => {
            if (confirm === "send") send();
            else {
              setConfirm(null);
              void action.act(
                (signal) => api.close(id, signal),
                () => remote.refresh(),
              );
            }
          }}
        >
          {confirm === "send" ? (
            <CostNotice />
          ) : (
            <p>Closing is terminal. Existing messages stay readable.</p>
          )}
        </Confirm>
      )}
    </>
  );
}
function MessageBubble({
  message,
  onRetrieve,
  busy,
}: {
  message: Message;
  onRetrieve: () => void;
  busy: boolean;
}) {
  return (
    <article className={`message-bubble message-${message.role.toLowerCase()}`}>
      <h3>
        {message.role === "USER" ? "You" : "Assistant"} ·{" "}
        <Status value={message.status} />
      </h3>
      {message.content && <p className="preserve">{message.content}</p>}
      {message.status === "FAILED" && (
        <p>
          This turn failed. Retrieve its durable outcome; no successful answer
          was produced.
        </p>
      )}
      {message.status === "RUNNING" && (
        <p>
          Pending or running. Refresh or retrieve the request outcome without
          resending.
        </p>
      )}
      <small>
        Message {message.ordinal} · <When value={message.createdAt} />
      </small>
      <Id value={message.sourceRunId} />
      {message.role === "ASSISTANT" && (
        <button disabled={busy} onClick={onRetrieve}>
          Check turn outcome
        </button>
      )}
      {message.citations.map((c) => (
        <div key={c.chunkId}>
          <strong>{c.filename}</strong>
          {c.sourcePage !== null && ` · Page ${c.sourcePage}`}
          <Id value={c.documentId} />
        </div>
      ))}
    </article>
  );
}
