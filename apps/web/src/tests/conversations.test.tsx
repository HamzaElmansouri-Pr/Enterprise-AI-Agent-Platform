import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Conversations } from "../features/conversations/Conversations";
import { ApiClient } from "../api/client";
import { TenantApi } from "../api/tenant";
import {
  agent,
  agentId,
  tenant,
  user,
  timestamp,
  run,
  runId,
  page,
  response,
} from "./fixtures";
const id = "90000000-0000-4000-8000-000000000010",
  requestId = "91000000-0000-4000-8000-000000000010";
const base = `/api/v1/tenants/${tenant}/agents/${agentId}/conversations`;
let conversation = {
  id,
  tenantId: tenant,
  agentId,
  createdBy: user,
  title: "Admissions conversation",
  status: "OPEN",
  createdAt: timestamp,
  updatedAt: timestamp,
};
let fetcher: ReturnType<typeof vi.fn>,
  pending: Map<string, string>,
  client: TenantApi,
  archived: boolean;
let messages: unknown[];
let override: (
  path: string,
  options: RequestInit,
) => Promise<Response> | Response | undefined;
const turn = { requestId, runId, status: "SUCCEEDED", result: run };
const assistant = {
  id: "92000000-0000-4000-8000-000000000010",
  requestId,
  ordinal: 2,
  role: "ASSISTANT",
  content: run.answer,
  status: "SUCCEEDED",
  citations: run.citations,
  sourceRunId: runId,
  createdAt: timestamp,
};
beforeEach(() => {
  pending = new Map();
  messages = [];
  archived = false;
  conversation = {
    ...conversation,
    status: "OPEN",
    title: "Admissions conversation",
  };
  override = () => undefined;
  vi.spyOn(crypto, "randomUUID").mockReturnValue(requestId);
  fetcher = vi.fn((path: string, options: RequestInit) => {
    const custom = override(path, options);
    if (custom) return Promise.resolve(custom);
    if (path.includes("/agents?"))
      return Promise.resolve(
        response(page([{ ...agent, status: archived ? "ARCHIVED" : "DRAFT" }])),
      );
    if (path.endsWith(`/agents/${agentId}`))
      return Promise.resolve(
        response({ ...agent, status: archived ? "ARCHIVED" : "DRAFT" }),
      );
    if (path === base && options.method === "POST")
      return Promise.resolve(response(conversation, 201));
    if (path === `${base}/${id}`) {
      if (options.method === "PUT")
        conversation = {
          ...conversation,
          ...JSON.parse(options.body as string),
        };
      return Promise.resolve(response(conversation));
    }
    if (path === `${base}/${id}/close`) {
      conversation = { ...conversation, status: "CLOSED" };
      return Promise.resolve(response(conversation));
    }
    if (path.includes("/messages?"))
      return Promise.resolve(response(page(messages)));
    if (path === `${base}/${id}/messages`) {
      messages = [assistant];
      return Promise.resolve(response(turn));
    }
    if (path.includes("/requests/")) return Promise.resolve(response(turn));
    if (path.startsWith(`${base}?`))
      return Promise.resolve(response(page([conversation])));
    return Promise.resolve(response({ code: "not_found" }, 404));
  });
  vi.stubGlobal("fetch", fetcher);
  client = new TenantApi(new ApiClient("test-session"), tenant);
});
function mount(acknowledged = true) {
  return render(
    <Conversations
      api={client}
      pending={pending}
      acknowledged={acknowledged}
      onAcknowledge={() => {}}
    />,
  );
}
async function select() {
  fireEvent.change(await screen.findByRole("combobox"), {
    target: { value: agentId },
  });
  await screen.findByRole("button", { name: "Open conversation" });
}
async function open() {
  await select();
  fireEvent.click(screen.getByRole("button", { name: "Open conversation" }));
  await screen.findByLabelText(/^Message/);
}
function enter() {
  fireEvent.change(screen.getByLabelText(/^Message/), {
    target: { value: "What documents?" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Send message" }));
}
const writes = () =>
  fetcher.mock.calls.filter(
    ([p, o]) => p === `${base}/${id}/messages` && o.method === "POST",
  );
describe("private conversations", () => {
  it("creates and opens a conversation without invoking a provider", async () => {
    mount();
    await select();
    fireEvent.change(screen.getByLabelText("Conversation title"), {
      target: { value: "Admissions" },
    });
    fireEvent.click(screen.getByRole("button", { name: "New conversation" }));
    await screen.findByLabelText(/^Message/);
    expect(writes()).toHaveLength(0);
    expect(
      JSON.parse(
        fetcher.mock.calls.find(
          ([p, o]) => p === base && o.method === "POST",
        )![1].body,
      ),
    ).toEqual({ title: "Admissions" });
  });
  it("sends one UUID per logical turn and displays real answer citations", async () => {
    mount();
    await open();
    enter();
    await screen.findAllByText(
      "Applicants need a passport and academic transcripts.",
    );
    expect(writes()).toHaveLength(1);
    expect(JSON.parse(writes()[0][1].body)).toEqual({
      requestId,
      message: "What documents?",
    });
    expect(pending.size).toBe(0);
    expect(screen.getAllByText("admissions.txt").length).toBeGreaterThan(0);
  });
  it("requires the existing explicit cost confirmation", async () => {
    mount(false);
    await open();
    enter();
    expect(writes()).toHaveLength(0);
    const dialog = await screen.findByRole("dialog");
    fireEvent.click(
      within(dialog).getByRole("button", { name: "Send message" }),
    );
    await waitFor(() => expect(writes()).toHaveLength(1));
  });
  it("blocks repeated clicks while pending without replacing the request ID", async () => {
    let finish!: (r: Response) => void;
    override = (p, o) =>
      p === `${base}/${id}/messages` && o.method === "POST"
        ? new Promise((r) => {
            finish = r;
          })
        : undefined;
    mount();
    await open();
    enter();
    fireEvent.click(screen.getByRole("button", { name: "Running…" }));
    expect(writes()).toHaveLength(1);
    expect(pending.get(`${base}/${id}`)).toBe(requestId);
    finish(response(turn));
    await waitFor(() => expect(pending.size).toBe(0));
  });
  it("retains uncertain request across feature unmount and only retrieves via GET", async () => {
    override = (p, o) => {
      if (p === `${base}/${id}/messages` && o.method === "POST")
        return Promise.reject(new TypeError("private-network-error"));
      return undefined;
    };
    let view = mount();
    await open();
    enter();
    await screen.findByRole("alert");
    expect(pending.size).toBe(1);
    view.unmount();
    view = mount();
    await open();
    fireEvent.click(
      screen.getByRole("button", { name: "Retrieve turn outcome" }),
    );
    await waitFor(() => expect(pending.size).toBe(0));
    expect(writes()).toHaveLength(1);
    const reads = fetcher.mock.calls.filter(([p]) => p.includes("/requests/"));
    expect(reads).toHaveLength(1);
    expect(reads[0][1].method).toBe("GET");
    expect(screen.queryByText("private-network-error")).toBeNull();
    view.unmount();
  });
  it("a missing outcome does not discard an ambiguous request ID", async () => {
    override = (path, options) => {
      if (path === `${base}/${id}/messages` && options.method === "POST")
        return Promise.reject(new TypeError("network"));
      if (path.includes("/requests/"))
        return response({ code: "not_found" }, 404);
      return undefined;
    };
    mount();
    await open();
    enter();
    await screen.findByRole("alert");
    fireEvent.click(
      screen.getByRole("button", { name: "Retrieve turn outcome" }),
    );
    await waitFor(() =>
      expect(fetcher.mock.calls.some(([p]) => p.includes("/requests/"))).toBe(
        true,
      ),
    );
    expect(pending.get(`${base}/${id}`)).toBe(requestId);
    expect(writes()).toHaveLength(1);
  });
  it("a definite rejected send releases the draft without an automatic retry", async () => {
    override = (path, options) =>
      path === `${base}/${id}/messages` && options.method === "POST"
        ? response({ code: "conversation_busy" }, 409)
        : undefined;
    mount();
    await open();
    enter();
    await screen.findByRole("alert");
    expect(pending.size).toBe(0);
    expect(writes()).toHaveLength(1);
  });
  it("keeps a running request pending and enables manual status retrieval", async () => {
    override = (p, o) =>
      p === `${base}/${id}/messages` && o.method === "POST"
        ? response({
            ...turn,
            status: "RUNNING",
            result: {
              ...run,
              status: "RUNNING",
              answer: null,
              citations: [],
              usage: { inputTokens: null, outputTokens: null },
              durationMs: null,
              modelId: null,
            },
          })
        : undefined;
    mount();
    await open();
    enter();
    await screen.findByRole("button", { name: "Retrieve turn outcome" });
    expect(pending.size).toBe(1);
    await waitFor(() =>
      expect(
        (
          screen.getByRole("button", {
            name: "Send message",
          }) as HTMLButtonElement
        ).disabled,
      ).toBe(true),
    );
  });
  it("can retrieve persisted turn metadata after reauthentication without session request state", async () => {
    messages = [assistant];
    mount();
    await open();
    fireEvent.click(screen.getByRole("button", { name: "Check turn outcome" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([p]) => p.endsWith(`/requests/${requestId}`)),
      ).toBe(true),
    );
    expect(writes()).toHaveLength(0);
  });
  it("closes only after explicit confirmation and then renders read-only", async () => {
    mount();
    await open();
    fireEvent.click(screen.getByRole("button", { name: "Close conversation" }));
    expect(fetcher.mock.calls.some(([p]) => p.endsWith("/close"))).toBe(false);
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {
        name: "Close conversation",
      }),
    );
    await waitFor(() => expect(screen.queryByLabelText(/^Message/)).toBeNull());
    expect(conversation.status).toBe("CLOSED");
  });
  it("renames with only the bounded title field", async () => {
    mount();
    await open();
    fireEvent.change(screen.getByLabelText("New conversation title"), {
      target: { value: "Renamed" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Rename conversation" }),
    );
    await screen.findByRole("heading", { name: /Renamed/ });
    expect(
      JSON.parse(
        fetcher.mock.calls.find(([, o]) => o.method === "PUT")![1].body,
      ),
    ).toEqual({ title: "Renamed" });
  });
  it("renders untrusted HTML literally and never persists browser secrets", async () => {
    messages = [{ ...assistant, content: "<img src=x onerror=alert(1)>" }];
    const save = vi.spyOn(Storage.prototype, "setItem");
    mount();
    await open();
    expect(screen.getByText("<img src=x onerror=alert(1)>")).toBeTruthy();
    expect(document.querySelector(".message-bubble img")).toBeNull();
    expect(save).not.toHaveBeenCalled();
  });
  it("supports chronological message pagination", async () => {
    override = (p) =>
      p.endsWith("/messages?page=0&size=20")
        ? response({ ...page([assistant]), hasNext: true })
        : undefined;
    mount();
    await open();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(([p]) =>
          p.endsWith("/messages?page=1&size=20"),
        ),
      ).toBe(true),
    );
  });
  it("archived agents expose history but disable new conversations and sends", async () => {
    archived = true;
    mount();
    await select();
    expect(
      (
        screen.getByRole("button", {
          name: "New conversation",
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Open conversation" }));
    await screen.findByText(
      "This agent is archived. Existing messages are read-only.",
    );
    expect(screen.queryByRole("button", { name: "Send message" })).toBeNull();
  });
});
