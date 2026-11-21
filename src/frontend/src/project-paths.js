/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import { branchFromSegment } from "./build-format";

// The manual lives at /manual and articles at /articles, so no project may use those names.
const MANUAL = "manual";
const ARTICLES = "articles";

/** Returns { slug } for /manual (slug null) or /manual/builds, otherwise null. */
export function manualFromPath(pathname) {
  const match = /^\/manual(?:\/([^/]*))?\/?$/i.exec(pathname);
  if (!match) return null;
  try {
    return { slug: match[1] ? decodeURIComponent(match[1]) : null };
  } catch {
    return { slug: null };
  }
}

export function manualPath(slug) {
  return slug ? `/${MANUAL}/${encodeURIComponent(slug)}` : `/${MANUAL}`;
}

/**
 * Returns { id, edit } for /articles (id null), /articles/new (edit), /articles/7 and
 * /articles/7/edit, otherwise null.
 */
export function articlesFromPath(pathname) {
  const match = /^\/articles(?:\/(new|\d+)(\/edit)?)?\/?$/i.exec(pathname);
  if (!match) return null;
  if (!match[1]) return { id: null, edit: false };
  if (match[1].toLowerCase() === "new")
    return match[2] ? null : { id: null, edit: true };
  return { id: Number(match[1]), edit: Boolean(match[2]) };
}

export function articlesPath({ id = null, edit = false } = {}) {
  if (id === null) return edit ? `/${ARTICLES}/new` : `/${ARTICLES}`;
  return `/${ARTICLES}/${id}${edit ? "/edit" : ""}`;
}

function isReserved(name) {
  const lower = name.toLowerCase();
  return lower === MANUAL || lower === ARTICLES;
}

/** Returns the project name for paths like /cisys/ (or /cisys), otherwise null. */
export function projectNameFromPath(pathname) {
  const match = /^\/([^/]+)\/?$/.exec(pathname);
  if (!match) return null;
  try {
    const name = decodeURIComponent(match[1]);
    return isReserved(name) ? null : name;
  } catch {
    return null;
  }
}

/** Returns { name, branch, buildId } for paths like /cisys/master/build/51, otherwise null. */
export function projectBuildFromPath(pathname) {
  const match = /^\/([^/]+)\/([^/]+)\/build\/(\d+)\/?$/.exec(pathname);
  if (!match) return null;
  try {
    if (isReserved(decodeURIComponent(match[1]))) return null;
    return {
      name: decodeURIComponent(match[1]),
      branch: branchFromSegment(decodeURIComponent(match[2])),
      buildId: Number(match[3]),
    };
  } catch {
    return null;
  }
}

/** The project page tab selected by a query string. Anything but "commits" is the default. */
export function readTabFromSearch(search) {
  return new URLSearchParams(search).get("tab") === "commits"
    ? "commits"
    : "builds";
}

/** The query string for a project tab, preserving every other query parameter. */
export function withTab(search, tab) {
  const params = new URLSearchParams(search);
  if (tab === "commits") params.set("tab", "commits");
  else params.delete("tab");
  const query = params.toString();
  return query ? `?${query}` : "";
}

export function projectPath(name, tab = "builds", search = "", hash = "") {
  return `/${encodeURIComponent(name)}/${withTab(search, tab)}${hash}`;
}

/** Returns { name } for paths like /cisys/settings, otherwise null. */
export function projectSettingsFromPath(pathname) {
  const match = /^\/([^/]+)\/settings\/?$/.exec(pathname);
  if (!match) return null;
  try {
    const name = decodeURIComponent(match[1]);
    return isReserved(name) ? null : { name };
  } catch {
    return null;
  }
}

export function projectSettingsPath(name) {
  return `${projectPath(name)}settings`;
}
