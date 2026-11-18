/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useState } from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import ProjectMembers from "./ProjectMembers";
import { toMap } from "./member-diff";

afterEach(cleanup);
vi.restoreAllMocks();

const leadUser = { username: "lead", subject: "lead-id", roles: ["lead"] };
const memberUser = {
  username: "member",
  subject: "member-id",
  roles: ["member"],
};

const led = [
  { username: "lead", role: "LEAD" },
  { username: "member", role: "MEMBER" },
];

/** Renders the members card with a real (but local) draft map. */
function renderCard({
  members = led,
  user = leadUser,
  isAdmin = false,
  busy = false,
  loadError = "",
  users = ["lead", "member", "neo"],
} = {}) {
  const Harness = () => {
    const [draft, setDraft] = useState(() => toMap(members));
    return (
      <ProjectMembers
        original={members}
        draft={draft}
        users={users}
        onDraftChange={setDraft}
        user={user}
        isAdmin={isAdmin}
        busy={busy}
        loadError={loadError}
      />
    );
  };
  return render(<Harness />);
}

describe("ProjectMembers (staged)", () => {
  it("lists the members with their roles", () => {
    const { container } = renderCard({});
    const rows = container.querySelectorAll(".member-row");
    expect(rows).toHaveLength(2);
    expect(rows[0].textContent).toContain("lead");
    expect(rows[1].textContent).toContain("member");
  });

  it("stages an addition locally without contacting the backend", () => {
    renderCard({});
    fireEvent.change(screen.getByLabelText("User"), {
      target: { value: "neo" },
    });
    fireEvent.change(screen.getByLabelText("Role"), {
      target: { value: "LEAD" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add member" }));
    expect(screen.getByText("neo")).toBeTruthy();
    expect(screen.getByLabelText("Role for neo").value).toBe("LEAD");
  });

  it("stages a role change locally without contacting the backend", () => {
    renderCard({});
    const select = screen.getByLabelText("Role for member");
    fireEvent.change(select, { target: { value: "LEAD" } });
    expect(select.value).toBe("LEAD");
  });

  it("does not send DELETE before Save when asked to remove a member", () => {
    renderCard({});
    fireEvent.click(
      screen.getByRole("button", { name: "Remove member from the project" }),
    );
    const dialog = screen.getByRole("alertdialog");
    expect(dialog.textContent).toContain("member (member)");
    expect(dialog.textContent).not.toContain("from this project");
    fireEvent.click(screen.getByRole("button", { name: "Remove" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(screen.queryByLabelText("Role for member")).toBeNull();
  });

  it("keeps the member when the removal pop-up is cancelled", () => {
    renderCard({});
    fireEvent.click(
      screen.getByRole("button", { name: "Remove member from the project" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(screen.getByLabelText("Role for member")).toBeTruthy();
  });

  it("offers only the users who are not on the team yet", () => {
    renderCard({});
    const options = [...screen.getByLabelText("User").options].map(
      (o) => o.value,
    );
    expect(options).toEqual(["", "neo"]);
  });

  it("disables the user drop-down when nobody can be added", () => {
    renderCard({ users: ["lead", "member"] });
    expect(screen.getByLabelText("User").disabled).toBe(true);
    expect(screen.getByText("No users available")).toBeTruthy();
  });

  it("lets a global admin manage the team without being one", () => {
    const admin = { username: "root", subject: "root-id", roles: ["admin"] };
    renderCard({ user: admin, isAdmin: true });
    expect(screen.getByLabelText("Role for lead")).toBeTruthy();
    expect(screen.getByLabelText("User")).toBeTruthy();
  });

  it("is read-only for a plain project member", () => {
    renderCard({ user: memberUser });
    expect(screen.queryByLabelText("User")).toBeNull();
    expect(screen.queryByLabelText("Role for member")).toBeNull();
    expect(
      screen.getByText(
        "Only the project's leads and admins can change the team.",
      ),
    ).toBeTruthy();
  });

  it("shows the load error instead of the empty-team state when the list fails to load", () => {
    renderCard({
      loadError: "You do not have permission to view this project's members.",
    });
    expect(
      screen.getByText(
        "You do not have permission to view this project's members.",
      ),
    ).toBeTruthy();
    expect(screen.queryByText("No members yet")).toBeNull();
    expect(screen.queryByLabelText("User")).toBeNull();
  });

  it("offers retry after a load failure and calls the retry handler", () => {
    const calls = [];
    const Harness = () => {
      const [draft, setDraft] = React.useState(() => toMap([]));
      return (
        <ProjectMembers
          original={[]}
          draft={draft}
          onDraftChange={setDraft}
          loadError="boom"
          user={{ username: "lead", subject: "x", roles: ["lead"] }}
          isAdmin={false}
          busy={false}
          onRetry={() => calls.push("retry")}
        />
      );
    };
    render(<Harness />);
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(calls).toEqual(["retry"]);
  });

  it("shows the empty state when the team loads as empty", () => {
    renderCard({ members: [] });
    expect(screen.getByText("No members yet")).toBeTruthy();
  });
});
