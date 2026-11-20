/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { describe, expect, it } from "vitest";
import {
  articlesFromPath,
  articlesPath,
  manualFromPath,
  manualPath,
  projectBuildFromPath,
  projectNameFromPath,
  projectSettingsFromPath,
} from "./project-paths";

describe("project-paths", () => {
  it("reads the manual paths", () => {
    expect(manualFromPath("/manual")).toEqual({ slug: null });
    expect(manualFromPath("/manual/")).toEqual({ slug: null });
    expect(manualFromPath("/manual/builds")).toEqual({ slug: "builds" });
    expect(manualFromPath("/cisys/")).toBeNull();
    expect(manualFromPath("/manual/a/b")).toBeNull();
    expect(manualPath("builds")).toBe("/manual/builds");
  });

  it("never reads the manual as a project", () => {
    expect(projectNameFromPath("/manual")).toBeNull();
    expect(projectNameFromPath("/Manual/")).toBeNull();
    expect(projectSettingsFromPath("/manual/settings")).toBeNull();
    expect(projectBuildFromPath("/manual/main/build/1")).toBeNull();
    expect(projectNameFromPath("/cisys/")).toBe("cisys");
  });

  it("reads the article paths", () => {
    expect(articlesFromPath("/articles")).toEqual({ id: null, edit: false });
    expect(articlesFromPath("/articles/")).toEqual({ id: null, edit: false });
    expect(articlesFromPath("/articles/new")).toEqual({ id: null, edit: true });
    expect(articlesFromPath("/articles/7")).toEqual({ id: 7, edit: false });
    expect(articlesFromPath("/articles/7/edit")).toEqual({ id: 7, edit: true });
    expect(articlesFromPath("/articles/new/edit")).toBeNull();
    expect(articlesFromPath("/articles/abc")).toBeNull();
    expect(articlesFromPath("/cisys/")).toBeNull();
    expect(articlesPath()).toBe("/articles");
    expect(articlesPath({ edit: true })).toBe("/articles/new");
    expect(articlesPath({ id: 7 })).toBe("/articles/7");
    expect(articlesPath({ id: 7, edit: true })).toBe("/articles/7/edit");
  });

  it("never reads articles as a project", () => {
    expect(projectNameFromPath("/articles")).toBeNull();
    expect(projectNameFromPath("/Articles/")).toBeNull();
    expect(projectSettingsFromPath("/articles/settings")).toBeNull();
    expect(projectBuildFromPath("/articles/main/build/1")).toBeNull();
  });
});
