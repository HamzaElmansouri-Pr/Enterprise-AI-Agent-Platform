import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { TenantSelection } from "./TenantSelection";
import { ApiClient } from "../api/client";
import { context, page, response, tenant } from "../tests/fixtures";
let rows: { tenantId: string; name: string; role: string }[];
let fetcher: ReturnType<typeof vi.fn>;
const other = "10000000-0000-4000-8000-000000000099";
beforeEach(() => {
  rows = [{ tenantId: tenant, name: "Atlas Learning Center", role: "OWNER" }];
  fetcher = vi.fn((path: string) =>
    Promise.resolve(
      response(path.includes("/me/tenants") ? page(rows) : context),
    ),
  );
  vi.stubGlobal("fetch", fetcher);
});
function selection(expired = vi.fn()) {
  const selected = vi.fn();
  const view = render(
    <TenantSelection
      api={new ApiClient("synthetic-token", expired)}
      onSelect={selected}
      onLogout={vi.fn()}
    />,
  );
  return { ...view, selected };
}
it("shows names and roles but requires explicit selection and fresh context", async () => {
  rows.push({ tenantId: other, name: "Rif Institute", role: "MEMBER" });
  const { selected } = selection();
  await screen.findByRole("heading", { name: "Atlas Learning Center" });
  expect(screen.getByRole("heading", { name: "Rif Institute" })).toBeTruthy();
  expect(selected).not.toHaveBeenCalled();
  expect(fetcher.mock.calls.some(([p]) => p.endsWith("/context"))).toBe(false);
  fireEvent.click(
    screen.getByRole("button", { name: "Open Atlas Learning Center" }),
  );
  await waitFor(() =>
    expect(selected).toHaveBeenCalledWith(context, "Atlas Learning Center"),
  );
});
it("handles no memberships without opening any workspace", async () => {
  rows = [];
  const { selected } = selection();
  await screen.findByText(/No organizations on this page/);
  expect(selected).not.toHaveBeenCalled();
});
it("refresh removes revoked membership from selectable organizations", async () => {
  selection();
  await screen.findByRole("button", { name: "Open Atlas Learning Center" });
  rows = [];
  fireEvent.click(
    screen.getByRole("button", { name: "Refresh organizations" }),
  );
  await screen.findByText(/No organizations on this page/);
  expect(
    screen.queryByRole("button", { name: "Open Atlas Learning Center" }),
  ).toBeNull();
});
it("a stale listed membership cannot bypass context denial or reveal private errors", async () => {
  fetcher.mockImplementation((path: string) =>
    Promise.resolve(
      path.endsWith("/context")
        ? response({ message: "private database detail" }, 403)
        : response(page(rows)),
    ),
  );
  const { selected } = selection();
  fireEvent.click(
    await screen.findByRole("button", { name: "Open Atlas Learning Center" }),
  );
  await screen.findByRole("alert");
  expect(selected).not.toHaveBeenCalled();
  expect(screen.queryByText("private database detail")).toBeNull();
});
it("discovery 401 ends the current session", async () => {
  fetcher.mockResolvedValue(response({}, 401));
  const expired = vi.fn();
  selection(expired);
  await waitFor(() => expect(expired).toHaveBeenCalledOnce());
});
it("unmount aborts discovery and cannot select a workspace from a late response", async () => {
  let signal: AbortSignal | null | undefined;
  fetcher.mockImplementation((_path: string, init: RequestInit) => {
    signal = init.signal;
    return new Promise(() => {});
  });
  const { unmount, selected } = selection();
  await waitFor(() => expect(signal).toBeTruthy());
  unmount();
  expect(signal?.aborted).toBe(true);
  expect(selected).not.toHaveBeenCalled();
});
