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
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import BuildStatistics, {
  percent,
  seconds,
  statisticsQuery,
} from "./BuildStatistics";

// Radix Select uses pointer capture and ResizeObserver, which jsdom does not implement.
beforeAll(() => {
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.setPointerCapture = () => {};
  Element.prototype.releasePointerCapture = () => {};
  Element.prototype.scrollIntoView = () => {};
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
});

afterEach(cleanup);

const row = (key, label, extra = {}) => ({
  key,
  label,
  builds: 4,
  passed: 2,
  failed: 1,
  cancelled: 1,
  active: 0,
  successRate: 2 / 3,
  averageSeconds: 95,
  lastBuildAt: null,
  ...extra,
});

const statistics = {
  projects: [
    { id: 1, name: "alpha" },
    { id: 2, name: "beta" },
  ],
  projectId: null,
  period: "30d",
  totals: row(null, ""),
  byProject: [row("1", "alpha")],
  byTrigger: [row("POLL", "POLL")],
  byBranch: [],
  recentFailures: [
    {
      id: 9,
      projectId: 1,
      project: "alpha",
      number: 3,
      branch: "main",
      revision: "0123456789",
      finishedAt: "2026-11-20T10:00:00Z",
    },
  ],
};

function choose(label, option) {
  const trigger = screen.getByLabelText(label);
  fireEvent.pointerDown(trigger, { button: 0, pointerType: "mouse" });
  fireEvent.pointerUp(trigger, { button: 0, pointerType: "mouse" });
  return screen
    .findByRole("option", { name: option })
    .then((o) => fireEvent.click(o));
}

describe("BuildStatistics", () => {
  it("formats values and queries", () => {
    expect(percent(null)).toBe("—");
    expect(percent(2 / 3)).toBe("67%");
    expect(seconds(null)).toBe("—");
    expect(seconds(42)).toBe("42s");
    expect(seconds(95)).toBe("1m 35s");
    expect(statisticsQuery("all", "30d")).toBe("?period=30d");
    expect(statisticsQuery("2", "all")).toBe("?period=all&projectId=2");
  });

  it("shows totals, tables and failures, and reloads on a filter change", async () => {
    const load = vi.fn().mockResolvedValue(statistics);
    const onOpenBuild = vi.fn();
    render(<BuildStatistics load={load} onOpenBuild={onOpenBuild} />);

    expect(await screen.findByText("Poll")).toBeTruthy();
    expect(load).toHaveBeenCalledWith("/reports/builds?period=30d");
    expect(screen.getAllByText("67%").length).toBeGreaterThan(0);
    expect(screen.getByText("0123456")).toBeTruthy();
    expect(screen.queryByText("Branches")).toBeNull();

    fireEvent.click(screen.getByText("#3"));
    expect(onOpenBuild).toHaveBeenCalledWith("alpha", "main", 9);

    await choose("Project", "beta");
    await waitFor(() =>
      expect(load).toHaveBeenLastCalledWith(
        "/reports/builds?period=30d&projectId=2",
      ),
    );
    await choose("Period", "All time");
    await waitFor(() =>
      expect(load).toHaveBeenLastCalledWith(
        "/reports/builds?period=all&projectId=2",
      ),
    );
  });

  it("downloads the PDF", async () => {
    const blob = new Blob(["%PDF"], { type: "application/pdf" });
    const load = vi.fn((path) =>
      path.includes("/pdf")
        ? Promise.resolve({
            headers: new Headers({
              "Content-Disposition":
                'attachment; filename="build-report-2026-11-21.pdf"',
            }),
            blob: () => Promise.resolve(blob),
          })
        : Promise.resolve(statistics),
    );
    URL.createObjectURL = vi.fn(() => "blob:report");
    URL.revokeObjectURL = vi.fn();
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, "click")
      .mockImplementation(() => {});
    render(<BuildStatistics load={load} />);
    await screen.findByText("Poll");

    fireEvent.click(screen.getByLabelText("Export PDF"));
    await waitFor(() => expect(click).toHaveBeenCalled());
    expect(load).toHaveBeenCalledWith(
      "/reports/builds/pdf?period=30d",
      expect.objectContaining({ raw: true }),
    );
    expect(URL.createObjectURL).toHaveBeenCalledWith(blob);
    click.mockRestore();
  });

  it("shows a load error", async () => {
    const load = vi.fn().mockRejectedValue(new Error("Forbidden"));
    render(<BuildStatistics load={load} />);
    expect((await screen.findByRole("alert")).textContent).toBe("Forbidden");
  });
});
