import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { FailureNotice } from "./FailureNotice";
describe("business failure recovery guidance", () => {
  it("explains why retrying a damaged source cannot repair it", () => {
    render(<FailureNotice code="INVALID_PDF" />);
    expect(screen.getByRole("alert").textContent).toContain(
      "Export a new, valid PDF",
    );
    expect(screen.getByRole("alert").textContent).toContain(
      "Retrying the same damaged file will not repair it",
    );
  });
  it("preserves uncertain paid outcomes instead of recommending automatic replay", () => {
    render(<FailureNotice code="PROVIDER_TIMEOUT" />);
    expect(screen.getByRole("alert").textContent).toContain(
      "provider charge may still have occurred",
    );
    expect(screen.getByRole("alert").textContent).toContain(
      "do not resend automatically",
    );
  });
  it("explains the runtime's sanitized failure category without inventing a cause", () => {
    render(<FailureNotice code="RUNTIME_FAILURE" />);
    expect(screen.getByRole("alert").textContent).toContain(
      "No successful answer was produced",
    );
    expect(screen.getByRole("alert").textContent).toContain(
      "Failure: RUNTIME_FAILURE",
    );
  });
  it("does not reflect arbitrary backend error details", () => {
    render(<FailureNotice code="private-provider-response" />);
    expect(screen.getByRole("alert").textContent).not.toContain(
      "private-provider-response",
    );
    expect(screen.getByRole("alert").textContent).toContain(
      "Failure: Unavailable",
    );
  });
  it("does not claim a failed internal action created a task", () => {
    render(<FailureNotice code="TOOL_EXECUTION_FAILED" />);
    expect(screen.getByRole("alert").textContent).toContain(
      "not confirmed as created",
    );
    expect(screen.getByRole("alert").textContent).toContain(
      "Retrieve the proposal outcome",
    );
    expect(screen.getByRole("alert").textContent).toContain(
      "Do not create a replacement proposal automatically",
    );
  });
  it("preserves billing uncertainty for a failed execution", () => {
    render(<FailureNotice code="EXECUTION_FAILED" />);
    expect(screen.getByRole("alert").textContent).toContain("durable outcome");
    expect(screen.getByRole("alert").textContent).toContain(
      "provider call may have occurred",
    );
  });
});
