import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { agent, context, page, response, tenant } from "./tests/fixtures";
let override: (
  path: string,
  init: RequestInit,
) => Response | Promise<Response> | undefined;
let fetcher: ReturnType<typeof vi.fn>;
beforeEach(() => {
  override = () => undefined;
  fetcher = vi.fn(
    (path: string, init: RequestInit) =>
      override(path, init) ??
      Promise.resolve(
        response(
          path.endsWith("/auth/login")
            ? {
                accessToken: "test-session-token",
                tokenType: "Bearer",
                expiresIn: 900,
              }
            : path.endsWith("/context")
              ? context
              : path.includes("/agents?")
                ? page([agent])
                : page([]),
        ),
      ),
  );
  vi.stubGlobal("fetch", fetcher);
});
async function login() {
  fireEvent.change(screen.getByLabelText("Email"), {
    target: { value: "operator@example.test" },
  });
  fireEvent.change(screen.getByLabelText("Password"), {
    target: { value: "synthetic-test-password" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
  await screen.findByRole("heading", { name: "Choose your organization" });
}
async function select() {
  fireEvent.change(screen.getByLabelText("Tenant UUID"), {
    target: { value: tenant },
  });
  fireEvent.click(screen.getByRole("button", { name: "Open workspace" }));
  await screen.findByRole("heading", { name: "Workspace overview" });
  await screen.findByText("Admissions Assistant");
}
describe("authentication and tenant lifecycle", () => {
  it("starts at login without restoring storage", () => {
    const storage = vi.spyOn(Storage.prototype, "getItem");
    render(<App />);
    expect(screen.getByRole("heading", { name: "Welcome back" })).toBeTruthy();
    expect(storage).not.toHaveBeenCalled();
  });
  it("logs in then requires verified tenant context", async () => {
    render(<App />);
    await login();
    expect(
      screen.queryByRole("heading", { name: "Workspace overview" }),
    ).toBeNull();
    await select();
    expect(screen.getByText("Current role:")).toBeTruthy();
    expect(fetcher.mock.calls.some(([p]) => p.endsWith("/context"))).toBe(true);
  });
  it("handles invalid credentials without session-expiry wording or private errors", async () => {
    override = (p) =>
      p.endsWith("/auth/login")
        ? response({ message: "secret" }, 401)
        : undefined;
    render(<App />);
    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: "bad@example.test" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "wrong" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
    await screen.findByText("Email or password is incorrect.");
    expect(screen.queryByText("secret")).toBeNull();
  });
  it("rejects malformed tenant UUID locally", async () => {
    render(<App />);
    await login();
    fireEvent.change(screen.getByLabelText("Tenant UUID"), {
      target: { value: "bad" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Open workspace" }));
    await screen.findByText("Enter a valid UUID.");
    expect(
      fetcher.mock.calls.filter(([p]) => p.endsWith("/context")),
    ).toHaveLength(0);
  });
  it("denies tenant selection after server 403", async () => {
    override = (p) => (p.endsWith("/context") ? response({}, 403) : undefined);
    render(<App />);
    await login();
    fireEvent.change(screen.getByLabelText("Tenant UUID"), {
      target: { value: tenant },
    });
    fireEvent.click(screen.getByRole("button", { name: "Open workspace" }));
    await screen.findByRole("alert");
    expect(
      screen.queryByRole("heading", { name: "Workspace overview" }),
    ).toBeNull();
  });
  it("logout clears all private views and requires new login", async () => {
    render(<App />);
    await login();
    await select();
    fireEvent.click(screen.getByRole("button", { name: "Sign out" }));
    await screen.findByRole("heading", { name: "Welcome back" });
    expect(screen.queryByText("Admissions Assistant")).toBeNull();
    expect(screen.queryByText(tenant)).toBeNull();
  });
  it("401 clears the console and reports expiry", async () => {
    render(<App />);
    await login();
    await select();
    override = (p) => (p.includes("/agents?") ? response({}, 401) : undefined);
    fireEvent.click(screen.getByRole("button", { name: "Agents" }));
    await screen.findByRole("heading", { name: "Welcome back" });
    expect(
      screen.getByText("Your session expired. Please sign in again."),
    ).toBeTruthy();
    expect(screen.queryByText("Admissions Assistant")).toBeNull();
  });
  it("tenant switch clears old data and aborts outstanding requests", async () => {
    render(<App />);
    await login();
    await select();
    let pendingSignal: AbortSignal | undefined;
    override = (p, init) => {
      if (p.includes("/tool-proposals?")) {
        pendingSignal = init.signal ?? undefined;
        return new Promise(() => {});
      }
    };
    fireEvent.click(screen.getByRole("button", { name: "Tool proposals" }));
    await waitFor(() => expect(pendingSignal).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Switch tenant" }));
    await screen.findByRole("heading", { name: "Choose your organization" });
    expect(pendingSignal?.aborted).toBe(true);
    expect(screen.queryByText("Admissions Assistant")).toBeNull();
  });
  it("MEMBER dashboard never assumes a total beyond a paginated response", async () => {
    override = (p) =>
      p.endsWith("/context")
        ? response({ ...context, role: "MEMBER" })
        : p.includes("/agents?")
          ? response(page([agent], true))
          : undefined;
    render(<App />);
    await login();
    await select();
    expect(
      screen.getByText("Shown on the first page; more available."),
    ).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Agents" }));
    await screen.findByRole("heading", { name: "Agent configurations" });
    expect(screen.queryByRole("button", { name: "Create agent" })).toBeNull();
  });
});

it("local token lifetime expires without making another API call", async () => {
  override = (p) =>
    p.endsWith("/auth/login")
      ? response({
          accessToken: "test-token",
          tokenType: "Bearer",
          expiresIn: 1,
        })
      : undefined;
  vi.useFakeTimers();
  try {
    render(<App />);
    fireEvent.change(screen.getByLabelText("Email"), {
      target: { value: "operator@example.test" },
    });
    fireEvent.change(screen.getByLabelText("Password"), {
      target: { value: "synthetic-test-password" },
    });
    await act(async () =>
      fireEvent.click(screen.getByRole("button", { name: "Sign in" })),
    );
    expect(
      screen.getByRole("heading", { name: "Choose your organization" }),
    ).toBeTruthy();
    const requestsBeforeExpiry = fetcher.mock.calls.length;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1100);
    });
    expect(screen.getByRole("heading", { name: "Welcome back" })).toBeTruthy();
    expect(fetcher).toHaveBeenCalledTimes(requestsBeforeExpiry);
  } finally {
    vi.useRealTimers();
  }
});
