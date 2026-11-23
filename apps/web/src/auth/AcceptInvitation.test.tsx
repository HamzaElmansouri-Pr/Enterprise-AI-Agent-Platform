import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ApiClient } from "../api/client";
import { AcceptInvitation } from "./AcceptInvitation";

describe("invitation token browser constraint", () => {
  it("rejects noncanonical characters and accepts URL-safe hyphens", () => {
    render(
      <AcceptInvitation
        api={new ApiClient(null)}
        existing={false}
        onBack={vi.fn()}
        onAccepted={vi.fn()}
      />,
    );
    const input = screen.getByLabelText(
      /^Invitation token/,
    ) as HTMLInputElement;
    fireEvent.change(input, { target: { value: "!".repeat(43) } });
    expect(input.validity.patternMismatch).toBe(true);
    fireEvent.change(input, { target: { value: "A_-".repeat(14) + "A" } });
    expect(input.validity.valid).toBe(true);
  });
});
