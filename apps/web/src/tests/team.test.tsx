import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiClient } from "../api/client";
import { TenantApi } from "../api/tenant";
import { Team } from "../features/team/Team";
import { AcceptInvitation } from "../auth/AcceptInvitation";
import App from "../App";
import {
  context,
  tenant,
  user,
  reviewer,
  timestamp,
  page,
  response,
} from "./fixtures";
const invitationId = "80000000-0000-4000-8000-000000000009",
  secret = "A".repeat(43);
const invitation = {
  id: invitationId,
  tenantId: tenant,
  email: "colleague@example.test",
  role: "MEMBER",
  invitedBy: user,
  status: "PENDING",
  createdAt: timestamp,
  expiresAt: "2026-11-24T03:00:00Z",
  expired: false,
  acceptedAt: null,
  acceptedBy: null,
  cancelledAt: null,
  cancelledBy: null,
};
const accepted = {
  invitationId,
  tenantId: tenant,
  userId: reviewer,
  role: "MEMBER",
};
let api: TenantApi,
  fetcher: ReturnType<typeof vi.fn>,
  role: "OWNER" | "ADMIN" | "MEMBER",
  invitations: (typeof invitation)[],
  override: (path: string, options: RequestInit) => Response | undefined;
beforeEach(() => {
  role = "OWNER";
  invitations = [];
  override = () => undefined;
  api = new TenantApi(new ApiClient("test-bearer"), tenant);
  fetcher = vi.fn((path: string, options: RequestInit) => {
    const custom = override(path, options);
    if (custom) return Promise.resolve(custom);
    if (path.endsWith("/context"))
      return Promise.resolve(response({ ...context, role }));
    if (path.includes("/members?"))
      return Promise.resolve(
        response(
          page([
            {
              userId: user,
              email: "owner@example.test",
              role: "OWNER",
              createdAt: timestamp,
            },
            {
              userId: reviewer,
              email: role === "MEMBER" ? null : "colleague@example.test",
              role: "MEMBER",
              createdAt: timestamp,
            },
          ]),
        ),
      );
    if (path.includes("/invitations?"))
      return Promise.resolve(response(page(invitations)));
    if (path.endsWith("/invitations") && options.method === "POST") {
      invitations = [invitation];
      return Promise.resolve(response({ invitation, token: secret }, 201));
    }
    if (path.endsWith("/cancel")) {
      invitations = [{ ...invitation, status: "CANCELLED" }];
      return Promise.resolve(response(invitations[0]));
    }
    if (path.includes("/members/"))
      return Promise.resolve(response({ completed: true }));
    if (path.endsWith("/accept-new") || path.endsWith("/accept-existing"))
      return Promise.resolve(
        response(accepted, path.endsWith("accept-new") ? 201 : 200),
      );
    return Promise.resolve(response(page([])));
  });
  vi.stubGlobal("fetch", fetcher);
  vi.stubGlobal("navigator", {
    clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
  });
});
const calls = (suffix: string) =>
  fetcher.mock.calls.filter(([p]) => p.endsWith(suffix));
async function create() {
  await screen.findByRole("heading", { name: "Invite a colleague" });
  fireEvent.change(screen.getByLabelText("Colleague email"), {
    target: { value: invitation.email },
  });
  fireEvent.click(screen.getByRole("button", { name: "Create invitation" }));
  await screen.findByLabelText("One-time invitation token");
}
describe("team management", () => {
  it("creates an invitation, displays token once and copies only on explicit action", async () => {
    render(<Team api={api} onContext={() => {}} />);
    await create();
    expect(
      (screen.getByLabelText("One-time invitation token") as HTMLInputElement)
        .value,
    ).toBe(secret);
    expect(JSON.parse(calls("/invitations")[0][1].body)).toEqual({
      email: invitation.email,
      role: "MEMBER",
    });
    fireEvent.click(screen.getByRole("button", { name: "Copy token" }));
    await waitFor(() =>
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(secret),
    );
    fireEvent.click(screen.getByRole("button", { name: "Dismiss token" }));
    expect(screen.queryByLabelText("One-time invitation token")).toBeNull();
    expect(calls("/invitations")).toHaveLength(1);
  });
  it("refresh and unmount clear one-time state without storing a token", async () => {
    const set = vi.spyOn(Storage.prototype, "setItem");
    const view = render(<Team api={api} onContext={() => {}} />);
    await create();
    fireEvent.click(screen.getByRole("button", { name: "Refresh access" }));
    await screen.findByRole("heading", { name: "Invite a colleague" });
    expect(screen.queryByLabelText("One-time invitation token")).toBeNull();
    view.unmount();
    render(<Team api={api} onContext={() => {}} />);
    await screen.findByRole("heading", { name: "Invite a colleague" });
    expect(screen.queryByLabelText("One-time invitation token")).toBeNull();
    expect(set).not.toHaveBeenCalled();
  });
  it("cancellation requires confirmation and does not expose another token", async () => {
    invitations = [invitation];
    render(<Team api={api} onContext={() => {}} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Cancel invitation" }),
    );
    expect(calls("/cancel")).toHaveLength(0);
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {
        name: "Cancel invitation",
      }),
    );
    await waitFor(() => expect(calls("/cancel")).toHaveLength(1));
    expect(screen.queryByLabelText("One-time invitation token")).toBeNull();
  });
  it("OWNER role change and revocation require separate confirmations", async () => {
    render(<Team api={api} onContext={() => {}} />);
    fireEvent.click(
      await screen.findByRole("button", { name: "Promote to ADMIN" }),
    );
    expect(calls("/role")).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "Change role" }));
    await waitFor(() => expect(calls("/role")).toHaveLength(1));
    expect(JSON.parse(calls("/role")[0][1].body)).toEqual({ role: "ADMIN" });
    fireEvent.click(
      await screen.findByRole("button", { name: "Revoke membership" }),
    );
    expect(
      fetcher.mock.calls.filter(([, o]) => o.method === "DELETE"),
    ).toHaveLength(0);
    fireEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {
        name: "Revoke membership",
      }),
    );
    await waitFor(() =>
      expect(
        fetcher.mock.calls.filter(([, o]) => o.method === "DELETE"),
      ).toHaveLength(1),
    );
  });
  it("ADMIN can invite only MEMBER and cannot promote", async () => {
    role = "ADMIN";
    invitations = [{ ...invitation, invitedBy: reviewer }];
    render(<Team api={api} onContext={() => {}} />);
    await screen.findByRole("heading", { name: "Invite a colleague" });
    expect(screen.queryByRole("option", { name: "ADMIN" })).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Promote to ADMIN" }),
    ).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Cancel invitation" }),
    ).toBeNull();
    expect(
      screen.getByRole("button", { name: "Revoke membership" }),
    ).toBeTruthy();
  });
  it("MEMBER sees limited directory and no administrative controls", async () => {
    role = "MEMBER";
    render(<Team api={api} onContext={() => {}} />);
    await screen.findByText("Current members");
    expect(
      screen.queryByRole("button", { name: "Create invitation" }),
    ).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Revoke membership" }),
    ).toBeNull();
    expect(
      screen.queryByRole("button", { name: "Promote to ADMIN" }),
    ).toBeNull();
    expect(fetcher.mock.calls.some(([p]) => p.includes("/invitations?"))).toBe(
      false,
    );
  });
});
describe("invitation acceptance forms", () => {
  function newForm(done = vi.fn()) {
    render(
      <AcceptInvitation
        existing={false}
        api={api.client}
        onBack={() => {}}
        onAccepted={done}
      />,
    );
    fireEvent.change(screen.getByLabelText(/Invitation token/), {
      target: { value: secret },
    });
    fireEvent.change(screen.getByLabelText("Invited email"), {
      target: { value: invitation.email },
    });
    fireEvent.change(screen.getByLabelText(/New password/), {
      target: { value: "synthetic-password-strong" },
    });
    return done;
  }
  it("new-account acceptance requires matching confirmation and uses no tenant/role body fields", async () => {
    const done = newForm();
    fireEvent.change(screen.getByLabelText("Confirm password"), {
      target: { value: "different-password" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Accept invitation" }));
    await screen.findByRole("alert");
    expect(calls("/accept-new")).toHaveLength(0);
    fireEvent.change(screen.getByLabelText("Confirm password"), {
      target: { value: "synthetic-password-strong" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Accept invitation" }));
    await waitFor(() => expect(done).toHaveBeenCalledWith(accepted));
    expect(JSON.parse(calls("/accept-new")[0][1].body)).toEqual({
      token: secret,
      email: invitation.email,
      password: "synthetic-password-strong",
    });
    expect(
      (screen.getByLabelText(/Invitation token/) as HTMLInputElement).value,
    ).toBe("");
  });
  it("existing-account acceptance sends only token with the current bearer", async () => {
    const done = vi.fn();
    render(
      <AcceptInvitation
        existing
        api={api.client}
        onBack={() => {}}
        onAccepted={done}
      />,
    );
    expect(screen.queryByLabelText(/New password/)).toBeNull();
    fireEvent.change(screen.getByLabelText(/Invitation token/), {
      target: { value: secret },
    });
    fireEvent.click(screen.getByRole("button", { name: "Accept invitation" }));
    await waitFor(() => expect(done).toHaveBeenCalledWith(accepted));
    expect(JSON.parse(calls("/accept-existing")[0][1].body)).toEqual({
      token: secret,
    });
    expect(calls("/accept-existing")[0][1].headers.Authorization).toBe(
      "Bearer test-bearer",
    );
  });
  it.each([410, 429, 403])(
    "handles acceptance HTTP %i without disclosure or retry",
    async (status) => {
      override = (p) =>
        p.endsWith("/accept-existing")
          ? response({ message: "private-secret" }, status)
          : undefined;
      render(
        <AcceptInvitation
          existing
          api={api.client}
          onBack={() => {}}
          onAccepted={() => {}}
        />,
      );
      fireEvent.change(screen.getByLabelText(/Invitation token/), {
        target: { value: secret },
      });
      fireEvent.click(
        screen.getByRole("button", { name: "Accept invitation" }),
      );
      await screen.findByRole("alert");
      expect(screen.queryByText("private-secret")).toBeNull();
      expect(calls("/accept-existing")).toHaveLength(1);
    },
  );
  it("session401 disposes the scope instead of retrying acceptance", async () => {
    const expired = vi.fn();
    const client = new ApiClient("token", expired);
    override = (p) =>
      p.endsWith("/accept-existing") ? response({}, 401) : undefined;
    render(
      <AcceptInvitation
        existing
        api={client}
        onBack={() => {}}
        onAccepted={() => {}}
      />,
    );
    fireEvent.change(screen.getByLabelText(/Invitation token/), {
      target: { value: secret },
    });
    fireEvent.click(screen.getByRole("button", { name: "Accept invitation" }));
    await waitFor(() => expect(expired).toHaveBeenCalledOnce());
    expect(calls("/accept-existing")).toHaveLength(1);
  });
  it("public new-account flow returns to normal login without issuing a session", async () => {
    render(<App />);
    fireEvent.click(
      screen.getByRole("button", { name: "Accept an invitation" }),
    );
    fireEvent.change(screen.getByLabelText(/Invitation token/), {
      target: { value: secret },
    });
    fireEvent.change(screen.getByLabelText("Invited email"), {
      target: { value: invitation.email },
    });
    fireEvent.change(screen.getByLabelText(/New password/), {
      target: { value: "synthetic-password-strong" },
    });
    fireEvent.change(screen.getByLabelText("Confirm password"), {
      target: { value: "synthetic-password-strong" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Accept invitation" }));
    await screen.findByRole("heading", { name: "Welcome back" });
    expect(
      screen.queryByRole("heading", { name: "Select your tenant" }),
    ).toBeNull();
    expect(calls("/accept-new")[0][1].headers.Authorization).toBeUndefined();
  });
});
