import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "../api/client";
import { TenantApi } from "../api/tenant";
import { Agents } from "../features/agents/Agents";
import { Knowledge, Search } from "../features/knowledge/Knowledge";
import { validateFile } from "../features/knowledge/validation";
import { Executions, RunResult } from "../features/executions/Executions";
import { Approvals, ProposalDetail } from "../features/approvals/Approvals";
import { Tasks } from "../features/admissions/Tasks";
import type { Proposal } from "../api/contracts";
import {
  agent,
  agentId,
  context,
  document,
  page,
  proposal,
  proposalId,
  response,
  reviewer,
  run,
  taskId,
  tenant,
  timestamp,
} from "./fixtures";
let api: TenantApi,
  fetcher: ReturnType<typeof vi.fn>,
  current: Proposal,
  documentStatus: string;
let override: (
  path: string,
  init: RequestInit,
) => Response | Promise<Response> | undefined;
beforeEach(() => {
  current = { ...proposal };
  documentStatus = "READY";
  override = () => undefined;
  api = new TenantApi(new ApiClient("memory-token"), tenant);
  fetcher = vi.fn((path: string, init: RequestInit) => {
    const custom = override(path, init);
    if (custom) return Promise.resolve(custom);
    if (path.includes("/agents?"))
      return Promise.resolve(response(page([agent])));
    if (path.endsWith("/approve")) {
      current = {
        ...current,
        status: "APPROVED",
        decision: {
          id: proposalId,
          approverId: reviewer,
          payloadDigest: current.payloadDigest,
          decision: "APPROVED",
          reason: null,
          decidedAt: timestamp,
        },
      };
      return Promise.resolve(response(current));
    }
    if (path.endsWith("/reject")) {
      current = { ...current, status: "REJECTED" };
      return Promise.resolve(response(current));
    }
    if (path.endsWith("/execute")) {
      current = {
        ...current,
        status: "SUCCEEDED",
        taskId,
        executedBy: reviewer,
      };
      return Promise.resolve(response(current));
    }
    if (path.endsWith("/tool-proposals/" + proposalId))
      return Promise.resolve(response(current));
    if (path.includes("/tool-proposals?"))
      return Promise.resolve(response(page([current])));
    if (path.includes("/knowledge/documents?"))
      return Promise.resolve(
        response(
          page([
            {
              ...document,
              status: documentStatus,
              chunkCount: documentStatus === "READY" ? 1 : 0,
              failureCode:
                documentStatus === "FAILED" ? "PROCESSING_FAILED" : null,
            },
          ]),
        ),
      );
    if (path.endsWith("/knowledge/documents/" + document.id))
      return Promise.resolve(response({ ...document, status: documentStatus }));
    if (path.endsWith("/runs")) return Promise.resolve(response(run));
    return Promise.resolve(response(agent));
  });
  vi.stubGlobal("fetch", fetcher);
});
afterEach(() => vi.useRealTimers());
const calls = (suffix: string) =>
  fetcher.mock.calls.filter(([path]) => path.endsWith(suffix));
async function chooseAgent() {
  await screen.findByRole("option", { name: "Admissions Assistant" });
  fireEvent.change(screen.getByRole("combobox", { name: /DRAFT agent/ }), {
    target: { value: agentId },
  });
}
function detail(
  userId = reviewer,
  role: "OWNER" | "ADMIN" | "MEMBER" = "ADMIN",
) {
  return render(
    <ProposalDetail
      api={api}
      id={proposalId}
      context={{ ...context, userId, role }}
      onBack={() => {}}
      onTasks={() => {}}
    />,
  );
}
describe("agent management", () => {
  it("paginates supported status filters", async () => {
    override = (p) =>
      p.includes("/agents?") ? response(page([agent], true)) : undefined;
    render(<Agents api={api} manager />);
    await screen.findByText("Admissions Assistant");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() =>
      expect(fetcher.mock.calls.some(([p]) => p.includes("page=1"))).toBe(true),
    );
    fireEvent.change(screen.getByLabelText("Status filter"), {
      target: { value: "ARCHIVED" },
    });
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([p]) => p.includes("page=0") && p.includes("status=ARCHIVED"),
        ),
      ).toBe(true),
    );
  });
  it("validates required creation fields and submits exact editable fields", async () => {
    render(<Agents api={api} manager />);
    fireEvent.click(screen.getByRole("button", { name: "Create agent" }));
    const save = screen.getByRole("button", {
      name: "Save configuration",
    }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText(/Name/), {
      target: { value: "New admissions agent" },
    });
    fireEvent.change(screen.getByLabelText(/System instructions/), {
      target: { value: "Use the admissions rules." },
    });
    fireEvent.click(save);
    await waitFor(() => expect(calls("/agents")).toHaveLength(1));
    expect(JSON.parse(calls("/agents")[0][1].body)).toEqual({
      name: "New admissions agent",
      description: "",
      systemInstructions: "Use the admissions rules.",
    });
  });
  it("archive requires a separate explicit confirmation", async () => {
    render(<Agents api={api} manager />);
    fireEvent.click(await screen.findByRole("button", { name: "View agent" }));
    fireEvent.click(
      await screen.findByRole("button", { name: "Archive agent" }),
    );
    expect(calls("/archive")).toHaveLength(0);
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/This is terminal/)).toBeTruthy();
    fireEvent.click(
      within(dialog).getByRole("button", { name: "Archive agent" }),
    );
    await waitFor(() => expect(calls("/archive")).toHaveLength(1));
  });
  it.each([403, 409])("shows sanitized server %i on update", async (status) => {
    override = (_p, i) =>
      i.method === "PUT" ? response({ detail: "private" }, status) : undefined;
    render(<Agents api={api} manager />);
    fireEvent.click(await screen.findByRole("button", { name: "View agent" }));
    fireEvent.click(
      await screen.findByRole("button", { name: "Edit configuration" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Save configuration" }));
    await screen.findAllByRole("alert");
    expect(screen.queryByText("private")).toBeNull();
  });
  it("MEMBER cannot access agent writes", async () => {
    render(<Agents api={api} manager={false} />);
    expect(screen.queryByRole("button", { name: "Create agent" })).toBeNull();
    fireEvent.click(await screen.findByRole("button", { name: "View agent" }));
    await screen.findByText("System instructions");
    expect(
      screen.queryByRole("button", { name: "Edit configuration" }),
    ).toBeNull();
    expect(screen.queryByRole("button", { name: "Archive agent" })).toBeNull();
  });
});
describe("knowledge", () => {
  it("rejects empty, oversize, and unsupported files", () => {
    expect(validateFile(null)).toBeTruthy();
    expect(
      validateFile(new File([], "empty.txt", { type: "text/plain" })),
    ).toBeTruthy();
    expect(
      validateFile(
        new File([new Uint8Array(2 * 1024 * 1024 + 1)], "big.txt", {
          type: "text/plain",
        }),
      ),
    ).toBeTruthy();
    expect(
      validateFile(new File(["x"], "x.html", { type: "text/html" })),
    ).toBeTruthy();
    expect(
      validateFile(new File(["hello"], "x.txt", { type: "text/plain" })),
    ).toBeNull();
  });
  it("uploads multipart with selected DRAFT association and renders PENDING", async () => {
    override = (p, i) =>
      i.method === "POST" && p.endsWith("/knowledge/documents")
        ? response({ ...document, status: "PENDING", chunkCount: 0 }, 202)
        : undefined;
    render(<Knowledge api={api} manager />);
    await chooseAgent();
    fireEvent.change(screen.getByLabelText("Document file"), {
      target: {
        files: [
          new File(["Passport needed."], "admissions.txt", {
            type: "text/plain",
          }),
        ],
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Upload document" }));
    await screen.findByText(/Document accepted/);
    expect(screen.getByText(/Document accepted/).textContent).not.toContain(
      "pending",
    );
    await screen.findByText("READY");
    const options = calls("/knowledge/documents")[0][1];
    expect(options.body).toBeInstanceOf(FormData);
    expect(options.body.get("agentId")).toBe(agentId);
    expect(options.headers["Content-Type"]).toBeUndefined();
  });
  it.each(["READY", "FAILED"])(
    "polls PENDING and stops after %s",
    async (terminal) => {
      documentStatus = "PENDING";
      vi.useFakeTimers();
      await act(async () => {
        render(<Knowledge api={api} manager={false} />);
      });
      expect(screen.getByText("PENDING")).toBeTruthy();
      documentStatus = terminal;
      await act(async () => {
        await vi.advanceTimersByTimeAsync(3100);
      });
      expect(screen.getByText(terminal)).toBeTruthy();
      const count = fetcher.mock.calls.length;
      await act(async () => {
        await vi.advanceTimersByTimeAsync(12000);
      });
      expect(fetcher.mock.calls.length).toBe(count);
    },
  );
  it("stops polling when unmounted", async () => {
    documentStatus = "PENDING";
    const view = render(<Knowledge api={api} manager={false} />);
    await screen.findByText("PENDING");
    vi.useFakeTimers();
    view.unmount();
    const count = fetcher.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000);
    });
    expect(fetcher.mock.calls.length).toBe(count);
  });
  it("retries FAILED documents only through explicit control", async () => {
    documentStatus = "FAILED";
    override = (p) =>
      p.endsWith("/retry")
        ? response({ ...document, status: "PENDING" }, 202)
        : undefined;
    render(<Knowledge api={api} manager />);
    fireEvent.click(
      await screen.findByRole("button", { name: "View document" }),
    );
    fireEvent.click(
      await screen.findByRole("button", { name: "Retry processing" }),
    );
    await waitFor(() => expect(calls("/retry")).toHaveLength(1));
  });
  it("shows real passage/filename/score and no invented page or HTML", async () => {
    override = (p) =>
      p.endsWith("/knowledge/search")
        ? response({
            results: [
              {
                documentId: document.id,
                filename: "admissions.txt",
                chunkId: document.id,
                text: "<img src=x onerror=alert(1)> Passport and transcripts.",
                score: 0.8493,
                sourcePage: null,
              },
            ],
          })
        : undefined;
    render(<Search api={api} />);
    await chooseAgent();
    fireEvent.change(screen.getByLabelText(/Search query/), {
      target: { value: "Admission documents" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Search knowledge" }));
    await screen.findByText(/Passport and transcripts/);
    expect(screen.getByText("Similarity: 0.8493")).toBeTruthy();
    expect(screen.queryByRole("img")).toBeNull();
    expect(screen.queryByText(/Page 1/)).toBeNull();
  });
});
describe("single-turn execution", () => {
  async function start() {
    await chooseAgent();
    fireEvent.change(screen.getByLabelText(/Question/), {
      target: { value: "What documents are required?" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Run question" }));
  }
  it("requires cost acknowledgment then displays answer and citations", async () => {
    const ack = vi.fn();
    render(<Executions api={api} acknowledged={false} onAcknowledge={ack} />);
    await start();
    expect(calls("/runs")).toHaveLength(0);
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {
        name: "Run question",
      }),
    );
    await screen.findByText(run.answer!);
    expect(ack).toHaveBeenCalledOnce();
    expect(screen.getByText("admissions.txt")).toBeTruthy();
    expect(calls("/runs")).toHaveLength(1);
  });
  it.each([503, 504, 429])(
    "handles HTTP %i without resubmitting",
    async (status) => {
      override = (p) =>
        p.endsWith("/runs") ? response({ code: "private" }, status) : undefined;
      render(<Executions api={api} acknowledged onAcknowledge={() => {}} />);
      await start();
      await screen.findAllByRole("alert");
      expect(calls("/runs")).toHaveLength(1);
      expect(screen.queryByText("private")).toBeNull();
    },
  );
  it("represents unknown actual usage as unavailable", () => {
    render(
      <RunResult
        run={{ ...run, usage: { inputTokens: null, outputTokens: null } }}
      />,
    );
    expect(screen.getAllByText("Unavailable")).toHaveLength(2);
  });
});
describe("human approval and task visibility", () => {
  it("PENDING has no task and creator cannot self-approve", async () => {
    detail(context.userId);
    const button = await screen.findByRole("button", {
      name: "Approve proposal",
    });
    expect((button as HTMLButtonElement).disabled).toBe(true);
    expect(screen.queryByText("Created task")).toBeNull();
    expect(calls("/execute")).toHaveLength(0);
  });
  it("different manager reviews exact digest and approval never executes", async () => {
    detail();
    fireEvent.click(
      await screen.findByRole("button", { name: "Approve proposal" }),
    );
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(proposal.payloadDigest)).toBeTruthy();
    expect(
      within(dialog).getByText(proposal.arguments.description),
    ).toBeTruthy();
    expect(calls("/approve")).toHaveLength(0);
    fireEvent.click(
      within(dialog).getByRole("button", { name: "Approve only" }),
    );
    await screen.findByRole("button", { name: "Execute approved action" });
    expect(JSON.parse(calls("/approve")[0][1].body)).toEqual({
      expectedPayloadDigest: proposal.payloadDigest,
    });
    expect(calls("/execute")).toHaveLength(0);
    expect(screen.queryByText("Created task")).toBeNull();
  });
  it("separate execution confirmation creates and displays a task", async () => {
    current = { ...proposal, status: "APPROVED" };
    detail();
    fireEvent.click(
      await screen.findByRole("button", { name: "Execute approved action" }),
    );
    expect(calls("/execute")).toHaveLength(0);
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByText(/creates one real OPEN task/)).toBeTruthy();
    fireEvent.click(
      within(dialog).getByRole("button", { name: "Execute and create task" }),
    );
    await screen.findByText("Created task");
    expect(screen.getByText(taskId)).toBeTruthy();
    expect(calls("/execute")).toHaveLength(1);
    expect(calls("/execute")[0][1].body).toBeUndefined();
  });
  it("blocks duplicate confirmation clicks while execution is pending", async () => {
    current = { ...proposal, status: "APPROVED" };
    let resolve: (r: Response) => void = () => {};
    override = (p) =>
      p.endsWith("/execute")
        ? new Promise((r) => {
            resolve = r;
          })
        : undefined;
    detail();
    fireEvent.click(
      await screen.findByRole("button", { name: "Execute approved action" }),
    );
    const button = within(screen.getByRole("dialog")).getByRole("button", {
      name: "Execute and create task",
    });
    fireEvent.click(button);
    fireEvent.click(button);
    expect(calls("/execute")).toHaveLength(1);
    expect((button as HTMLButtonElement).disabled).toBe(true);
    current = { ...current, status: "SUCCEEDED", taskId };
    await act(async () => resolve(response(current)));
    await screen.findByText("Created task");
  });
  it("rejection requires a reason and blocks execution", async () => {
    detail();
    fireEvent.click(
      await screen.findByRole("button", { name: "Reject proposal" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Reject permanently" }));
    expect(calls("/reject")).toHaveLength(0);
    fireEvent.change(screen.getByLabelText(/Rejection reason/), {
      target: { value: "Needs correction." },
    });
    fireEvent.click(screen.getByRole("button", { name: "Reject permanently" }));
    await waitFor(() => expect(calls("/reject")).toHaveLength(1));
    await screen.findByText("REJECTED");
    expect(
      screen.queryByRole("button", { name: "Execute approved action" }),
    ).toBeNull();
  });
  it("MEMBER cannot approve/reject/execute", async () => {
    detail(reviewer, "MEMBER");
    await screen.findByText("Review proposal");
    expect(
      screen.queryByRole("button", { name: "Approve proposal" }),
    ).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Reject proposal" }),
    ).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Execute approved action" }),
    ).toBeNull();
  });
  it("stale digest 409 is displayed without execution", async () => {
    override = (p) =>
      p.endsWith("/approve")
        ? response({ code: "proposal_digest_mismatch" }, 409)
        : undefined;
    detail();
    fireEvent.click(
      await screen.findByRole("button", { name: "Approve proposal" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Approve only" }));
    await screen.findAllByRole("alert");
    expect(calls("/execute")).toHaveLength(0);
  });
  it("MEMBER creation offers only AI generation and never calls automatically", async () => {
    render(
      <Approvals
        api={api}
        context={{ ...context, role: "MEMBER" }}
        onTasks={() => {}}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "New proposal" }));
    await screen.findByLabelText("Intent");
    expect(screen.queryByLabelText("Task title")).toBeNull();
    expect(calls("/generate")).toHaveLength(0);
  });
  it("renders immutable successful replay with the same task ID", async () => {
    current = { ...proposal, status: "SUCCEEDED", taskId };
    const view = detail();
    await screen.findByText(taskId);
    fireEvent.click(screen.getByRole("button", { name: "Refresh proposal" }));
    await screen.findByText(taskId);
    expect(calls("/execute")).toHaveLength(0);
    view.unmount();
  });
  it("task list exposes audit fields and no write controls", async () => {
    override = (p) =>
      p.includes("/admissions/followup-tasks?")
        ? response(
            page([
              {
                id: taskId,
                tenantId: tenant,
                sourceProposalId: proposalId,
                createdBy: reviewer,
                ...proposal.arguments,
                status: "OPEN",
                createdAt: timestamp,
              },
            ]),
          )
        : undefined;
    render(<Tasks api={api} />);
    await screen.findByText("Review admission documents");
    expect(screen.getByText(taskId)).toBeTruthy();
    expect(screen.getByText("OPEN")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Delete|Edit/ })).toBeNull();
  });
});

it("document polling is bounded to sixty sequential automatic refreshes", async () => {
  documentStatus = "PENDING";
  vi.useFakeTimers();
  await act(async () => {
    render(<Knowledge api={api} manager={false} />);
  });
  for (let i = 0; i < 65; i++)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3100);
    });
  const documentCalls = fetcher.mock.calls.filter(([p]) =>
    p.includes("/knowledge/documents?"),
  );
  expect(documentCalls).toHaveLength(61);
});

it("manual proposal submission sends only the fixed tool schema and never executes", async () => {
  override = (p, i) =>
    p.endsWith("/tool-proposals") && i.method === "POST"
      ? response(proposal, 201)
      : undefined;
  render(<Approvals api={api} context={context} onTasks={() => {}} />);
  fireEvent.click(screen.getByRole("button", { name: "New proposal" }));
  await chooseAgent();
  fireEvent.change(screen.getByLabelText(/Task title/), {
    target: { value: proposal.arguments.title },
  });
  fireEvent.change(screen.getByLabelText("Task description"), {
    target: { value: proposal.arguments.description },
  });
  fireEvent.change(screen.getByLabelText(/Rationale/), {
    target: { value: proposal.rationale },
  });
  fireEvent.click(
    screen.getByRole("button", { name: "Submit unapproved proposal" }),
  );
  await screen.findByRole("heading", { name: "Review proposal" });
  expect(JSON.parse(calls("/tool-proposals")[0][1].body)).toEqual({
    toolName: proposal.toolName,
    arguments: proposal.arguments,
    rationale: proposal.rationale,
  });
  expect(calls("/execute")).toHaveLength(0);
  expect(calls("/approve")).toHaveLength(0);
});

it("retrieving an existing run does not attribute an earlier question to it", async () => {
  override = (p) => (p.includes("/runs/") ? response(run) : undefined);
  render(<Executions api={api} acknowledged onAcknowledge={() => {}} />);
  await chooseAgent();
  fireEvent.change(screen.getByLabelText(/Question/), {
    target: { value: "Original question" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Run question" }));
  await screen.findByText(run.answer!);
  expect(
    screen.getByRole("heading", { name: "Submitted question" }),
  ).toBeTruthy();
  fireEvent.change(screen.getByLabelText("Run UUID"), {
    target: { value: run.runId },
  });
  fireEvent.click(screen.getByRole("button", { name: "Retrieve run" }));
  await screen.findByText(run.answer!);
  expect(
    screen.queryByRole("heading", { name: "Submitted question" }),
  ).toBeNull();
  expect(calls("/runs")).toHaveLength(1);
});
