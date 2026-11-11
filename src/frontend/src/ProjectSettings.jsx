/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useState } from "react";
import { Save, X } from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "./components/ui/card";
import PageHeader from "./components/layout/PageHeader";
import { projectPath } from "./ProjectBuilds";

/** Returns the project name for paths like /cisys/settings, otherwise null. */
export function projectSettingsFromPath(pathname) {
  const match = /^\/([^/]+)\/settings\/?$/.exec(pathname);
  if (!match) return null;
  try {
    return { name: decodeURIComponent(match[1]) };
  } catch {
    return null;
  }
}

export function projectSettingsPath(name) {
  return `${projectPath(name)}settings`;
}

/**
 * Dedicated page for a project's settings. `onSaved` receives the updated project; `onCancel`
 * leaves the page without saving.
 */
export default function ProjectSettings({
  project,
  api,
  isAdmin,
  onSaved,
  onCancel,
}) {
  const [name, setName] = useState(project.name);
  const [url, setUrl] = useState(project.repositoryUrl);
  const [priv, setPriv] = useState(project.privateRepository);
  const [credential, setCredential] = useState(project.credentialRef || "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // Only admins can set a project credential, and only a private project can have one.
  const showCredential = isAdmin && priv;

  async function submit(event) {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      const body = { name, repositoryUrl: url, privateRepository: priv };
      // A lead's save leaves the credential untouched.
      if (isAdmin)
        body.credentialRef =
          priv && credential.trim() ? credential.trim() : null;
      onSaved(
        await api(`/projects/${project.id}`, {
          method: "PUT",
          body: JSON.stringify(body),
        }),
      );
    } catch (e) {
      setError(e.message || "Unable to save the project.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="page-container">
      <PageHeader
        title="Project settings"
        subtitle={project.name}
        actions={
          <>
            <Button
              type="submit"
              form="project-settings-form"
              size="icon"
              className="h-10 w-10"
              disabled={busy}
              title="Save"
              aria-label="Save project settings"
            >
              <Save size={16} aria-hidden="true" />
            </Button>
            <Button
              type="button"
              variant="outline"
              size="icon"
              className="h-10 w-10"
              disabled={busy}
              onClick={onCancel}
              title="Cancel"
              aria-label="Cancel project settings"
            >
              <X size={16} aria-hidden="true" />
            </Button>
          </>
        }
      />
      {error && (
        <div className="error-message" role="alert">
          {error}
        </div>
      )}
      <Card className="project-card">
        <CardHeader>
          <CardTitle>Repository</CardTitle>
        </CardHeader>
        <CardContent>
          <form
            id="project-settings-form"
            className="settings-form"
            onSubmit={submit}
          >
            <div className="form-field">
              <Label htmlFor="settings-name">Project name</Label>
              <Input
                id="settings-name"
                required
                maxLength="100"
                pattern="[A-Za-z0-9][A-Za-z0-9._\-]*"
                title="Project names use letters, digits, '.', '_' and '-', and start with a letter or digit"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="my-project"
              />
            </div>
            <div className="form-field">
              <Label htmlFor="settings-url">Repository URL</Label>
              <Input
                id="settings-url"
                required
                type="url"
                value={url}
                onChange={(e) => setUrl(e.target.value)}
                placeholder="https://github.com/org/repo.git"
              />
            </div>
            <div className="form-field">
              <Label htmlFor="settings-private" className="whitespace-nowrap">
                Private project
              </Label>
              <span className="private-choice">
                <input
                  id="settings-private"
                  type="checkbox"
                  checked={priv}
                  onChange={(e) => setPriv(e.target.checked)}
                />
              </span>
            </div>
            {showCredential && (
              <div className="form-field">
                <Label htmlFor="settings-credential">
                  Credential reference
                </Label>
                <Input
                  id="settings-credential"
                  maxLength="90"
                  pattern="CISYS_GIT_[A-Z0-9_]{1,80}"
                  title="An environment variable named CISYS_GIT_..."
                  value={credential}
                  onChange={(e) => setCredential(e.target.value)}
                  placeholder="CISYS_GIT_PROJECT"
                />
              </div>
            )}
          </form>
        </CardContent>
      </Card>
    </main>
  );
}
