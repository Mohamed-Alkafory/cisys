/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import BuildReports from "./BuildReports";

afterEach(cleanup);

describe("BuildReports", () => {
  it("shows nothing when the build has no reports", async () => {
    const load = vi.fn().mockResolvedValue([]);
    const { container } = render(
      <BuildReports name="p" buildId={1} load={load} />,
    );
    await waitFor(() =>
      expect(load).toHaveBeenCalledWith("/project-pages/p/builds/1/reports"),
    );
    expect(container.innerHTML).toBe("");
  });

  it("shows a text report and puts an HTML report in a sandboxed frame", async () => {
    const load = vi.fn().mockResolvedValue([
      {
        type: "list",
        name: "List",
        format: "TEXT",
        content: "  README.md\n",
        truncated: false,
      },
      {
        type: "wile",
        name: "Wile",
        format: "HTML",
        content: "<video></video>",
        truncated: true,
      },
    ]);
    render(<BuildReports name="p" buildId={1} load={load} />);
    expect(await screen.findByRole("tab", { name: "List" })).toBeTruthy();
    expect(screen.getByText("README.md")).toBeTruthy();
    fireEvent.mouseDown(screen.getByRole("tab", { name: "Wile" }));
    fireEvent.click(screen.getByRole("tab", { name: "Wile" }));
    const frame = await screen.findByTitle("Wile");
    expect(frame.getAttribute("sandbox")).toBe("");
    expect(frame.getAttribute("srcdoc")).toBe("<video></video>");
    expect(
      screen.getByText("The report was cut at its size limit."),
    ).toBeTruthy();
  });
});
