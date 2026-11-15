/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React from "react";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import ProjectSettings from "./ProjectSettings";

afterEach(cleanup);

function project(id = 1) {
  return {
    id,
    name: "cisys",
    repositoryUrl: "https://github.com/org/repo",
    privateRepository: false,
    credentialRef: null,
  };
}

function renderSettings(api, target = project(), isAdmin = false) {
  return render(
    <ProjectSettings
      project={target}
      api={api}
      isAdmin={isAdmin}
      onSaved={() => {}}
      onCancel={() => {}}
    />,
  );
}

describe("ProjectSettings", () => {
  it("shows the repository fields", () => {
    renderSettings(vi.fn());
    expect(screen.getByLabelText("Project name").value).toBe("cisys");
    expect(screen.getByLabelText("Repository URL").value).toBe(
      "https://github.com/org/repo",
    );
  });

  it("saves the project from the repository card only", async () => {
    const saved = { ...project(), name: "renamed" };
    const api = vi.fn(async () => saved);
    const onSaved = vi.fn();
    render(
      <ProjectSettings
        project={project()}
        api={api}
        isAdmin={false}
        onSaved={onSaved}
        onCancel={() => {}}
      />,
    );

    fireEvent.change(screen.getByLabelText("Project name"), {
      target: { value: "renamed" },
    });
    fireEvent.submit(document.getElementById("project-settings-form"));

    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(saved));
    expect(api).toHaveBeenCalledWith(
      "/projects/1",
      expect.objectContaining({ method: "PUT" }),
    );
    expect(screen.queryByText("Automatic builds")).toBeNull();
  });
});
