import React from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import ProjectSettings from "./ProjectSettings";

afterEach(cleanup);

function settings(overrides = {}) {
  return {
    enabled: false,
    url: "https://cisys.example/api/webhooks/git/1",
    branches: "*",
    secretSet: true,
    ...overrides,
  };
}

function project(id = 1) {
  return {
    id,
    name: "cisys",
    repositoryUrl: "https://github.com/org/repo",
    privateRepository: false,
    credentialRef: null,
  };
}

function renderSettings(api, target = project()) {
  return render(
    <ProjectSettings
      project={target}
      api={api}
      isAdmin={false}
      onSaved={() => {}}
      onCancel={() => {}}
    />,
  );
}

describe("ProjectSettings webhooks", () => {
  it("asks before rotating an existing secret", async () => {
    const api = vi.fn(async (url, options) => {
      if (url.endsWith("/webhook/secret") && options?.method === "POST")
        return { secret: "rotated-secret" };
      return settings();
    });
    renderSettings(api);

    fireEvent.click(
      await screen.findByRole("button", { name: "Rotate webhook secret" }),
    );
    expect(
      screen.getByRole("alertdialog", { name: /confirm secret rotation/i }),
    ).toBeTruthy();
    const rotateSecret = screen.getByRole("button", { name: /rotate secret/i });
    await waitFor(() => expect(document.activeElement).toBe(rotateSecret));
    expect(
      api.mock.calls.some(
        ([url, options]) =>
          String(url).endsWith("/webhook/secret") && options?.method === "POST",
      ),
    ).toBe(false);

    fireEvent.click(rotateSecret);
    await waitFor(() =>
      expect(screen.getByDisplayValue("rotated-secret")).toBeTruthy(),
    );
  });

  it("closes the rotation confirmation when the secret is revealed", async () => {
    const api = vi.fn(async (url) =>
      String(url).endsWith("/webhook/secret")
        ? { secret: "revealed-secret" }
        : settings(),
    );
    renderSettings(api);

    fireEvent.click(
      await screen.findByRole("button", { name: "Rotate webhook secret" }),
    );
    expect(
      screen.getByRole("alertdialog", { name: /confirm secret rotation/i }),
    ).toBeTruthy();

    fireEvent.click(
      screen.getByRole("button", { name: "Reveal webhook secret" }),
    );
    await screen.findByDisplayValue("revealed-secret");
    expect(
      screen.queryByRole("alertdialog", { name: /confirm secret rotation/i }),
    ).toBeNull();
  });

  it("cancels a rotation without calling the backend", async () => {
    const api = vi.fn(async () => settings());
    renderSettings(api);

    fireEvent.click(
      await screen.findByRole("button", { name: "Rotate webhook secret" }),
    );
    fireEvent.click(screen.getByRole("button", { name: "Cancel rotation" }));

    expect(
      screen.queryByRole("alertdialog", { name: /confirm secret rotation/i }),
    ).toBeNull();
    expect(
      api.mock.calls.some(([, options]) => options?.method === "POST"),
    ).toBe(false);
  });

  it("clears a revealed secret when the project changes", async () => {
    const api = vi.fn(async (url) =>
      String(url).endsWith("/webhook/secret")
        ? { secret: "visible-secret" }
        : settings(),
    );
    const { rerender } = renderSettings(api);

    fireEvent.click(
      await screen.findByRole("button", { name: "Reveal webhook secret" }),
    );
    await screen.findByDisplayValue("visible-secret");

    rerender(
      <ProjectSettings
        project={project(2)}
        api={api}
        isAdmin={false}
        onSaved={() => {}}
        onCancel={() => {}}
      />,
    );
    await waitFor(() =>
      expect(screen.queryByDisplayValue("visible-secret")).toBeNull(),
    );
  });
});
