import React from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import UsersPanel from "./UsersPanel";

const users = {
  total: 2,
  users: [
    {
      id: "a",
      username: "boss",
      role: "admin",
      enabled: true,
      manageable: false,
      projects: [],
    },
    {
      id: "m",
      username: "dev",
      role: "member",
      enabled: true,
      manageable: true,
      projects: [{ id: 1, name: "alpha" }],
    },
  ],
};

function api() {
  return vi.fn((path) =>
    Promise.resolve(
      path === "/users/projects" ? [{ id: 1, name: "alpha" }] : users,
    ),
  );
}

afterEach(cleanup);

describe("UsersPanel", () => {
  it("lets a lead add members only and manage only members", async () => {
    render(<UsersPanel api={api()} user={{ roles: ["lead"], subject: "l" }} />);
    expect(await screen.findByText("dev")).toBeTruthy();
    expect(screen.queryByLabelText("Role")).toBeNull();
    expect(screen.queryByLabelText("Edit boss")).toBeNull();
    expect(screen.getByLabelText("Edit dev")).toBeTruthy();
    expect(screen.queryByLabelText("Delete dev")).toBeNull();
  });

  it("lets an admin choose roles and delete others", async () => {
    const admin = { ...users.users[0], manageable: true };
    const load = vi.fn((path) =>
      Promise.resolve(
        path === "/users/projects"
          ? []
          : { total: 2, users: [admin, users.users[1]] },
      ),
    );
    render(<UsersPanel api={load} user={{ roles: ["admin"], subject: "a" }} />);
    expect(await screen.findByText("dev")).toBeTruthy();
    expect(screen.getAllByLabelText("Role").length).toBe(2);
    expect(screen.getByLabelText("Delete dev")).toBeTruthy();
    expect(screen.queryByLabelText("Delete boss")).toBeNull();
  });
});
