import React from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import UsersPanel from "./UsersPanel";

// Radix Select and Popover use pointer capture and ResizeObserver, which jsdom
// does not implement.
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

  it("saves a project assignment chosen from the picker", async () => {
    const admin = { ...users.users[0], manageable: true };
    const calls = [];
    const load = vi.fn((path, options) => {
      calls.push({ path, options });
      return Promise.resolve(
        path === "/users/projects"
          ? [
              { id: 1, name: "alpha" },
              { id: 2, name: "beta" },
            ]
          : { total: 2, users: [admin, users.users[1]] },
      );
    });
    render(<UsersPanel api={load} user={{ roles: ["admin"], subject: "a" }} />);

    fireEvent.click(await screen.findByLabelText("Edit dev"));
    fireEvent.click(screen.getByLabelText("Projects for dev"));
    fireEvent.click(await screen.findByLabelText("beta"));
    fireEvent.click(screen.getByRole("button", { name: "Done" }));
    await choose("Role for dev", "lead");
    fireEvent.click(screen.getByLabelText("Save dev"));

    await waitFor(() => {
      const put = calls.find(
        (call) => call.path === "/users/m" && call.options?.method === "PUT",
      );
      expect(put).toBeTruthy();
      expect(JSON.parse(put.options.body)).toEqual({
        id: "m",
        email: "",
        firstName: "",
        lastName: "",
        role: "lead",
        enabled: true,
        projectIds: [1, 2],
      });
    });
  });
});
