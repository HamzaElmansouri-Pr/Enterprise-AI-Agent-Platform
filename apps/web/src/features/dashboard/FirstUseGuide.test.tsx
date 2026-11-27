import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { FirstUseGuide } from "./FirstUseGuide";
import { agent, context, document } from "../../tests/fixtures";

it("derives progress from actual READY association and never dispatches a model", () => {
  const navigate = vi.fn(),
    fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  render(
    <FirstUseGuide
      context={context}
      agents={[agent]}
      documents={[document]}
      invitationFound={false}
      navigate={navigate}
    />,
  );
  expect(screen.getAllByText("Found in current records")).toHaveLength(3);
  expect(screen.getAllByText("Not observed on these pages")).toHaveLength(1);
  fireEvent.click(
    screen.getByRole("button", { name: /Go to understand ai answers/i }),
  );
  expect(navigate).toHaveBeenCalledWith("conversations");
  expect(fetcher).not.toHaveBeenCalled();
  expect(screen.getByText(/Availability is not checked here/)).toBeTruthy();
});
it("does not count a failed file or unrelated association as ready knowledge", () => {
  render(
    <FirstUseGuide
      context={{ ...context, role: "MEMBER" }}
      agents={[agent]}
      documents={[{ ...document, agentIds: [] }]}
      invitationFound={null}
      navigate={vi.fn()}
    />,
  );
  expect(screen.getAllByText("Not observed on these pages")).toHaveLength(1);
  expect(screen.queryByText("Invite a colleague")).toBeNull();
});
