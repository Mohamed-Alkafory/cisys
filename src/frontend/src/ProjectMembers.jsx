/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useState } from "react";
import { Trash2, Users, UserPlus } from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Badge } from "./components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "./components/ui/card";
import { cloneMap, toMap } from "./member-diff";

const ROLES = ["MEMBER", "LEAD"];
const SELECT =
  "h-10 w-full rounded-md border border-input bg-transparent px-3 text-sm";

function eq(a, b) {
  return a && b && a.toLowerCase() === b.toLowerCase();
}

const key = (username) => username.toLowerCase();

/**
 * Team management for one project, shown on the Team tab of the project's settings page.
 * Edits are staged locally and applied by the page's Save button against the
 * project team endpoints; the backend remains the authority — it re-checks every
 * rule that protects the last LEAD, the project creator and project authorization.
 */
export default function ProjectMembers({
  original, // server members or null while loading: [{username, role}]
  draft, // Map(lowercase -> {username, role}), the desired end state
  onDraftChange, // (nextDraft: Map) => void
  loadError, // string | "" — the list itself failed to load
  user,
  isAdmin,
  busy, // a save is in flight
  onRetry, // () => void — re-fetch members after a load failure
}) {
  const [name, setName] = useState("");
  const [role, setRole] = useState("MEMBER");
  const [addError, setAddError] = useState("");

  const originalMap = toMap(original ?? []);
  const selfKey = key(user?.username ?? "");
  const me = draft?.get(selfKey);
  // A failed team-list load makes the team read-only: the UI cannot know the baseline.
  const canManage = !loadError && (isAdmin || me?.role === "LEAD");
  const readOnly = original !== null && draft !== null && !canManage;

  // Rows: original members in server order first, then staged additions appended.
  const rows = [];
  for (const m of original ?? []) {
    const k = key(m.username);
    const entry = draft?.get(k);
    if (!draft || entry) {
      rows.push({ username: m.username, role: entry?.role ?? m.role });
    }
  }
  if (draft) {
    for (const [k, entry] of draft) {
      if (!originalMap.has(k)) {
        rows.push({ username: entry.username, role: entry.role });
      }
    }
  }

  function changeRole(row, nextRole) {
    if (row.role === nextRole || !canManage || busy) return;
    const next = cloneMap(draft);
    next.set(key(row.username), { username: row.username, role: nextRole });
    onDraftChange?.(next);
  }

  function remove(row) {
    if (!canManage || busy) return;
    const k = key(row.username);
    const next = cloneMap(draft);
    if (next.has(k) && !originalMap.has(k)) {
      // Staged addition: drop it from the plan entirely.
      next.delete(k);
    } else {
      // Server member: mark them as pending removal.
      if (!window.confirm(`Remove ${row.username} from this project?`)) return;
      next.delete(k);
    }
    onDraftChange?.(next);
  }

  function addPending(event) {
    event.preventDefault();
    if (!draft || !canManage || busy) return;
    const username = name.trim();
    if (!username) return setAddError("Enter a Keycloak username.");
    const k = key(username);
    if (draft.has(k)) {
      setAddError(`'${username}' is already a member of this project.`);
      return;
    }
    const next = cloneMap(draft);
    next.set(k, { username, role });
    onDraftChange?.(next);
    setName("");
    setRole("MEMBER");
    setAddError("");
  }

  return (
    <Card className="project-card">
      <CardHeader>
        <CardTitle>Members</CardTitle>
      </CardHeader>
      <CardContent>
        <p className="members-note">
          MEMBER can view the project and start builds. LEAD can also edit the
          project, pin builds and manage the team. Only existing Keycloak users
          can be added. Changes are applied when you press Save.
        </p>
        {loadError && (
          <div className="error-message" role="alert">
            <span className="block">{loadError}</span>
            {onRetry && (
              <Button
                type="button"
                variant="outline"
                className="mt-3"
                onClick={onRetry}
              >
                Retry
              </Button>
            )}
          </div>
        )}
        {original === null && !loadError ? (
          <p className="loading-state" role="status">
            Loading members…
          </p>
        ) : loadError ? null : rows.length === 0 ? (
          <div className="empty-state">
            <Users size={28} aria-hidden="true" />
            <strong>No members yet</strong>
            {canManage && <p>Add an existing Keycloak user below.</p>}
          </div>
        ) : (
          <ul className="members-list">
            {rows.map((row) => {
              const self = eq(row.username, user?.username);
              return (
                <li className="member-row" key={row.username}>
                  <span className="member-name">
                    {row.username}
                    {self && " (you)"}
                  </span>
                  <Badge variant="outline" className="justify-self-start">
                    {row.role}
                  </Badge>
                  {canManage ? (
                    <div className="member-actions">
                      <select
                        className={SELECT}
                        aria-label={`Role for ${row.username}`}
                        value={row.role}
                        disabled={busy}
                        onChange={(e) => changeRole(row, e.target.value)}
                      >
                        {ROLES.map((r) => (
                          <option key={r} value={r}>
                            {r}
                          </option>
                        ))}
                      </select>
                      <Button
                        type="button"
                        variant="outline"
                        size="icon"
                        disabled={busy}
                        title={`Remove ${row.username}`}
                        aria-label={`Remove ${row.username} from the project`}
                        onClick={() => remove(row)}
                      >
                        <Trash2 size={16} aria-hidden="true" />
                      </Button>
                    </div>
                  ) : null}
                </li>
              );
            })}
          </ul>
        )}
        {canManage && (
          <form className="member-add" onSubmit={addPending}>
            <div className="form-field">
              <Label htmlFor="member-username">Add a member</Label>
              <Input
                id="member-username"
                required
                value={name}
                maxLength={255}
                autoComplete="off"
                placeholder="Keycloak username"
                disabled={busy}
                onChange={(e) => setName(e.target.value)}
              />
              {addError && (
                <p className="member-add-error" role="alert">
                  {addError}
                </p>
              )}
            </div>
            <div className="form-field">
              <Label htmlFor="member-role">Role</Label>
              <select
                id="member-role"
                className={SELECT}
                value={role}
                disabled={busy}
                onChange={(e) => setRole(e.target.value)}
              >
                {ROLES.map((r) => (
                  <option key={r} value={r}>
                    {r}
                  </option>
                ))}
              </select>
            </div>
            <Button
              type="submit"
              size="icon"
              className="h-10 w-10"
              disabled={busy || !name.trim()}
              title="Add member"
              aria-label="Add member"
            >
              <UserPlus size={16} aria-hidden="true" />
            </Button>
          </form>
        )}
        {readOnly && !loadError && (
          <p className="members-note">
            Only the project&apos;s LEADs and admins can change the team.
          </p>
        )}
      </CardContent>
    </Card>
  );
}
