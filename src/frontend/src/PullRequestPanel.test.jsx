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
import PullRequestPanel from "./PullRequestPanel";

afterEach(cleanup);

describe("PullRequestPanel", () => {
  it("loads the pull request setting", async () => {
    const api = vi.fn(async () => ({ enabled: true }));
    render(<PullRequestPanel api={api} />);

    await waitFor(() =>
      expect(screen.getByLabelText("Pull request builds").checked).toBe(true),
    );
    expect(api).toHaveBeenCalledWith("/settings/pull-requests");
    expect(screen.getByText("Build pull requests")).toBeTruthy();
  });

  it("saves the toggle", async () => {
    const api = vi
      .fn()
      .mockResolvedValueOnce({ enabled: true })
      .mockResolvedValueOnce({ enabled: false });
    render(<PullRequestPanel api={api} />);
    await waitFor(() =>
      expect(screen.getByLabelText("Pull request builds").checked).toBe(true),
    );

    fireEvent.click(screen.getByLabelText("Pull request builds"));
    fireEvent.submit(
      screen.getByLabelText("Save pull request settings").closest("form"),
    );

    await waitFor(() =>
      expect(api).toHaveBeenCalledWith(
        "/settings/pull-requests",
        expect.objectContaining({ method: "PUT" }),
      ),
    );
    expect(JSON.parse(api.mock.calls[1][1].body)).toEqual({ enabled: false });
    await screen.findByRole("status");
  });

  it("shows a server rejection", async () => {
    const api = vi
      .fn()
      .mockResolvedValueOnce({ enabled: false })
      .mockRejectedValueOnce(new Error("Only admins may change this setting"));
    render(<PullRequestPanel api={api} />);
    await waitFor(() =>
      expect(screen.getByLabelText("Pull request builds").checked).toBe(false),
    );

    fireEvent.submit(
      screen.getByLabelText("Save pull request settings").closest("form"),
    );

    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain(
        "Only admins may change this setting",
      ),
    );
  });
});
