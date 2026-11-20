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
import PollingPanel from "./PollingPanel";

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

const SAVED = {
  enabled: true,
  granularity: "PER_COMMIT",
  scope: "WORKFLOW",
  intervalSeconds: 60,
  mirrorManualBuilds: false,
};

async function choose(label, option) {
  const trigger = screen.getByLabelText(label);
  fireEvent.pointerDown(trigger, {
    button: 0,
    ctrlKey: false,
    pointerType: "mouse",
  });
  fireEvent.pointerUp(trigger, {
    button: 0,
    ctrlKey: false,
    pointerType: "mouse",
  });
  fireEvent.click(await screen.findByRole("option", { name: option }));
}

function selected(label) {
  return screen.getByLabelText(label).textContent;
}

describe("PollingPanel", () => {
  it("loads the automatic build settings", async () => {
    const api = vi.fn(async () => SAVED);
    render(<PollingPanel api={api} />);

    await waitFor(() =>
      expect(screen.getByLabelText("Poll interval (seconds)").value).toBe("60"),
    );
    expect(api).toHaveBeenCalledWith("/settings/polling");
    expect(screen.getByLabelText("Automatic builds").checked).toBe(true);
    expect(selected("Granularity")).toContain("One build per commit");
    expect(selected("Branches")).toContain(
      "All branches with .cisys/workflows/ci.yml",
    );
  });

  it("saves the granularity, scope and interval as numbers", async () => {
    const api = vi.fn().mockResolvedValueOnce(SAVED).mockResolvedValueOnce({
      enabled: false,
      granularity: "TIP",
      scope: "MAIN_ONLY",
      intervalSeconds: 120,
    });
    render(<PollingPanel api={api} />);
    await waitFor(() =>
      expect(selected("Granularity")).toContain("One build per commit"),
    );

    fireEvent.click(screen.getByLabelText("Automatic builds"));
    await choose("Granularity", "Tip only");
    await choose("Branches", "main or master only");
    fireEvent.change(screen.getByLabelText("Poll interval (seconds)"), {
      target: { value: "120" },
    });
    fireEvent.submit(
      screen.getByLabelText("Save automatic build settings").closest("form"),
    );

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith(
        "/settings/polling",
        expect.objectContaining({ method: "PUT" }),
      ),
    );
    const body = JSON.parse(api.mock.calls[1][1].body);
    expect(body).toEqual({
      enabled: false,
      granularity: "TIP",
      scope: "MAIN_ONLY",
      intervalSeconds: 120,
      mirrorManualBuilds: false,
    });
  });

  it("sends null when the interval was not changed", async () => {
    const api = vi
      .fn()
      .mockResolvedValueOnce(SAVED)
      .mockResolvedValueOnce(SAVED);
    render(<PollingPanel api={api} />);
    await waitFor(() =>
      expect(selected("Granularity")).toContain("One build per commit"),
    );

    fireEvent.click(screen.getByLabelText("Automatic builds"));
    fireEvent.submit(
      screen.getByLabelText("Save automatic build settings").closest("form"),
    );

    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    const body = JSON.parse(api.mock.calls[1][1].body);
    expect(body.intervalSeconds).toBeNull();
  });

  it("saves the manual build mirror choice", async () => {
    const api = vi
      .fn()
      .mockResolvedValueOnce(SAVED)
      .mockResolvedValueOnce({ ...SAVED, mirrorManualBuilds: true });
    render(<PollingPanel api={api} />);
    const box = await screen.findByLabelText("Manual builds");
    expect(box.checked).toBe(false);

    fireEvent.click(box);
    fireEvent.submit(
      screen.getByLabelText("Save automatic build settings").closest("form"),
    );

    await waitFor(() => expect(api).toHaveBeenCalledTimes(2));
    expect(JSON.parse(api.mock.calls[1][1].body).mirrorManualBuilds).toBe(true);
    await waitFor(() =>
      expect(screen.getByLabelText("Manual builds").checked).toBe(true),
    );
  });

  it("clears a stale error when a field is edited", async () => {
    const api = vi
      .fn()
      .mockResolvedValueOnce(SAVED)
      .mockRejectedValueOnce(
        new Error("Poll interval must be between 5 and 86400 seconds"),
      );
    render(<PollingPanel api={api} />);
    await waitFor(() =>
      expect(selected("Granularity")).toContain("One build per commit"),
    );

    fireEvent.submit(
      screen.getByLabelText("Save automatic build settings").closest("form"),
    );
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain("Poll interval"),
    );

    await choose("Granularity", "Tip only");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("shows a server rejection", async () => {
    const api = vi
      .fn()
      .mockResolvedValueOnce(SAVED)
      .mockRejectedValueOnce(
        new Error("Poll interval must be between 5 and 86400 seconds"),
      );
    render(<PollingPanel api={api} />);
    await waitFor(() =>
      expect(selected("Granularity")).toContain("One build per commit"),
    );

    fireEvent.submit(
      screen.getByLabelText("Save automatic build settings").closest("form"),
    );

    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain(
        "Poll interval must be between",
      ),
    );
  });
});
