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
import ProjectCommits from "./ProjectCommits";

afterEach(cleanup);

const revision = (letter) => letter.repeat(40);

const commit = (letter, message, builds = []) => ({
  revision: revision(letter),
  message,
  author: "Ada",
  authoredAt: "2026-01-02T03:04:05Z",
  builds,
});

function renderCommits({ commits = [], hasMore = false, reason = null } = {}) {
  const load = vi.fn(async () => ({ commits, hasMore, reason }));
  const onOpenBuild = vi.fn();
  render(
    <ProjectCommits
      name="demo"
      branch="main"
      load={load}
      onOpenBuild={onOpenBuild}
    />,
  );
  return { load, onOpenBuild };
}

describe("ProjectCommits", () => {
  it("shows a loading state until the first page arrives", () => {
    render(
      <ProjectCommits
        name="demo"
        branch="main"
        load={() => new Promise(() => {})}
        onOpenBuild={() => {}}
      />,
    );
    expect(screen.getByRole("status").textContent).toContain("Loading commits");
  });

  it("renders commits and opens build links", async () => {
    const { load, onOpenBuild } = renderCommits({
      commits: [
        commit("a", "Add feature", [
          { id: 11, number: 3, status: "PASSED", branch: "main" },
        ]),
      ],
    });

    expect(await screen.findByText("Add feature")).toBeTruthy();
    expect(screen.getByText("aaaaaaa")).toBeTruthy();
    expect(screen.getByText("Ada")).toBeTruthy();
    const link = screen.getByRole("link", { name: "#3" });
    expect(link.getAttribute("href")).toBe("/demo/main/build/11");
    fireEvent.click(link);
    expect(onOpenBuild).toHaveBeenCalledWith("demo", "main", 11);
    expect(load).toHaveBeenCalledWith(
      "/project-pages/demo/commits?branch=main&skip=0&limit=50",
    );
  });

  it("loads older commits and removes duplicate revisions", async () => {
    const first = commit("a", "One");
    const duplicate = commit("b", "Two");
    const third = commit("c", "Three");
    const load = vi
      .fn()
      .mockResolvedValueOnce({
        commits: [first, duplicate],
        hasMore: true,
        reason: null,
      })
      .mockResolvedValueOnce({
        commits: [duplicate, third],
        hasMore: false,
        reason: null,
      });
    render(
      <ProjectCommits
        name="demo"
        branch="main"
        load={load}
        onOpenBuild={() => {}}
      />,
    );

    expect(await screen.findByText("One")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Load older commits" }));
    expect(await screen.findByText("Three")).toBeTruthy();
    expect(screen.getAllByText("Two")).toHaveLength(1);
    expect(load).toHaveBeenNthCalledWith(
      2,
      "/project-pages/demo/commits?branch=main&skip=2&limit=50",
    );
  });

  it("resets the list when the branch changes", async () => {
    const load = vi.fn(async (path) =>
      path.includes("branch=dev")
        ? { commits: [commit("d", "Dev commit")], hasMore: false, reason: null }
        : {
            commits: [commit("a", "Main commit")],
            hasMore: false,
            reason: null,
          },
    );
    const { rerender } = render(
      <ProjectCommits
        name="demo"
        branch="main"
        load={load}
        onOpenBuild={() => {}}
      />,
    );

    expect(await screen.findByText("Main commit")).toBeTruthy();
    rerender(
      <ProjectCommits
        name="demo"
        branch="dev"
        load={load}
        onOpenBuild={() => {}}
      />,
    );

    expect(await screen.findByText("Dev commit")).toBeTruthy();
    expect(screen.queryByText("Main commit")).toBeNull();
    expect(load).toHaveBeenCalledWith(
      "/project-pages/demo/commits?branch=dev&skip=0&limit=50",
    );
  });

  it("explains an unavailable history", async () => {
    renderCommits({
      commits: [commit("a", "Only built commit")],
      reason: "HISTORY_UNAVAILABLE",
    });

    expect(await screen.findByText(/History is unavailable/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Load older commits" })).toBe(
      null,
    );
  });

  it("explains the history limit and does not offer more", async () => {
    renderCommits({
      commits: [commit("a", "Old commit")],
      hasMore: true,
      reason: "HISTORY_LIMIT",
    });

    expect(await screen.findByText(/maximum history depth/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Load older commits" })).toBe(
      null,
    );
  });

  it("shows an empty state", async () => {
    renderCommits();
    expect(await screen.findByText("No commits")).toBeTruthy();
  });

  it("shows an error and retries", async () => {
    const load = vi
      .fn()
      .mockRejectedValueOnce(new Error("boom"))
      .mockResolvedValueOnce({
        commits: [commit("a", "Recovered")],
        hasMore: false,
        reason: null,
      });
    render(
      <ProjectCommits
        name="demo"
        branch="main"
        load={load}
        onOpenBuild={() => {}}
      />,
    );

    expect((await screen.findByRole("alert")).textContent).toContain("boom");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Recovered")).toBeTruthy();
    await waitFor(() => expect(load).toHaveBeenCalledTimes(2));
  });

  it("uses a friendly message when the mirror is busy", async () => {
    const load = vi
      .fn()
      .mockRejectedValueOnce(Object.assign(new Error("busy"), { status: 503 }))
      .mockResolvedValueOnce({
        commits: [commit("a", "Recovered")],
        hasMore: false,
        reason: null,
      });
    render(
      <ProjectCommits
        name="demo"
        branch="main"
        load={load}
        onOpenBuild={() => {}}
      />,
    );

    expect((await screen.findByRole("alert")).textContent).toMatch(
      /try again shortly/i,
    );
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Recovered")).toBeTruthy();
  });

  it("waits for Retry-After before allowing another retry", async () => {
    const load = vi
      .fn()
      .mockRejectedValueOnce(
        Object.assign(new Error("busy"), { status: 503, retryAfter: 1 }),
      )
      .mockResolvedValueOnce({
        commits: [commit("a", "Recovered")],
        hasMore: false,
        reason: null,
      });
    render(
      <ProjectCommits
        name="demo"
        branch="main"
        load={load}
        onOpenBuild={() => {}}
      />,
    );

    expect((await screen.findByRole("alert")).textContent).toMatch(
      /try again in 1 second/i,
    );
    const retry = screen.getByRole("button", { name: "Try again" });
    expect(retry.disabled).toBe(true);
    await waitFor(() => expect(retry.disabled).toBe(false), {
      timeout: 2500,
    });
    fireEvent.click(retry);
    expect(await screen.findByText("Recovered")).toBeTruthy();
  });
});
