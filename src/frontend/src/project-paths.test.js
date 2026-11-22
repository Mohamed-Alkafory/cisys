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
  projectPath,
  projectSettingsFromPath,
  readTabFromSearch,
  routeFromPath,
  withTab,
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

  it("reads every route from one path", () => {
    expect(routeFromPath("/")).toEqual({ page: "projects" });
    expect(routeFromPath("/images")).toEqual({ page: "images" });
    expect(routeFromPath("/Settings/")).toEqual({ page: "settings" });
    expect(routeFromPath("/manual/builds")).toEqual({
      manual: { slug: "builds" },
    });
    expect(routeFromPath("/articles/7")).toEqual({
      articles: { id: 7, edit: false },
    });
    expect(routeFromPath("/cisys/")).toEqual({ project: "cisys" });
    expect(routeFromPath("/cisys/settings")).toEqual({
      project: "cisys",
      settings: true,
    });
    expect(routeFromPath("/cisys/main/build/51")).toEqual({
      project: "cisys",
      branch: "main",
      buildId: 51,
    });
    expect(routeFromPath("/a/b/c")).toBeNull();
  });

  it("never reads a top-level page as a project", () => {
    expect(projectNameFromPath("/users")).toBeNull();
    expect(projectSettingsFromPath("/settings/settings")).toBeNull();
    expect(projectBuildFromPath("/home/main/build/1")).toBeNull();
    expect(projectNameFromPath("/audit")).toBeNull();
  });

  it("reads only the commits tab from the query string", () => {
    expect(readTabFromSearch("")).toBe("builds");
    expect(readTabFromSearch("?tab=builds")).toBe("builds");
    expect(readTabFromSearch("?tab=commits")).toBe("commits");
    expect(readTabFromSearch("?tab=other")).toBe("builds");
    expect(readTabFromSearch("?tab=COMMITS")).toBe("builds");
  });

  it("updates the tab without losing other query parameters", () => {
    expect(withTab("", "builds")).toBe("");
    expect(withTab("?tab=commits", "builds")).toBe("");
    expect(withTab("?tab=commits&x=1", "builds")).toBe("?x=1");
    expect(withTab("?branch=feature%2Fx&tab=builds&x=1", "commits")).toBe(
      "?branch=feature%2Fx&tab=commits&x=1",
    );
    expect(withTab("?branch=feature%2Fx&x=1", "commits")).toBe(
      "?branch=feature%2Fx&x=1&tab=commits",
    );
  });

  it("builds project paths with a clean or commits URL", () => {
    expect(projectPath("cisys")).toBe("/cisys/");
    expect(projectPath("cisys", "builds")).toBe("/cisys/");
    expect(projectPath("cisys", "commits")).toBe("/cisys/?tab=commits");
    expect(projectPath("a b", "commits")).toBe("/a%20b/?tab=commits");
    expect(projectPath("cisys", "commits", "?x=1", "#details")).toBe(
      "/cisys/?x=1&tab=commits#details",
    );
    expect(projectPath("cisys", "builds", "?x=1", "#details")).toBe(
      "/cisys/?x=1#details",
    );
  });

  it("matches routes from the pathname even when a query string is present", () => {
    const url = new URL("/cisys/?tab=commits&x=1", "http://localhost");
    expect(projectNameFromPath(url.pathname)).toBe("cisys");
    expect(projectBuildFromPath("/cisys/feature%2Fx/build/7")).toEqual({
      name: "cisys",
      branch: "feature/x",
      buildId: 7,
    });
  });
});
