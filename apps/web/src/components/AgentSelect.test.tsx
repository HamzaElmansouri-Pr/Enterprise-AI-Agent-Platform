import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { AgentSelect } from "./AgentSelect";
import { ApiClient } from "../api/client";
import { TenantApi } from "../api/tenant";
import { agent, page, response, tenant } from "../tests/fixtures";

it("a failed agent read offers deliberate GET recovery without retrying automatically", async () => {
  const fetcher = vi
    .fn()
    .mockRejectedValueOnce(new TypeError("connection failed"))
    .mockResolvedValueOnce(response(page([agent])));
  vi.stubGlobal("fetch", fetcher);
  render(
    <AgentSelect
      api={new TenantApi(new ApiClient("synthetic-token"), tenant)}
      value=""
      onChange={vi.fn()}
    />,
  );
  await screen.findByRole("alert");
  expect(fetcher).toHaveBeenCalledOnce();
  fireEvent.click(screen.getByRole("button", { name: "Refresh agents" }));
  await screen.findByRole("option", { name: agent.name });
  await waitFor(() => expect(fetcher).toHaveBeenCalledTimes(2));
  for (const [, options] of fetcher.mock.calls)
    expect(options.method).toBe("GET");
});
