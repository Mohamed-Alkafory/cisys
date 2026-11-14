/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import { Copy, KeyRound, RefreshCw, Save, X } from "lucide-react";
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
  const [webhook, setWebhook] = useState(null);
  const [webhookEnabled, setWebhookEnabled] = useState(false);
  const [branches, setBranches] = useState("*");
  const [secret, setSecret] = useState("");
  const [secretSet, setSecretSet] = useState(false);
  const [webhookBusy, setWebhookBusy] = useState(false);
  const [webhookError, setWebhookError] = useState("");

  // Only admins can set a project credential, and only a private project can have one.
  const showCredential = isAdmin && priv;

  useEffect(() => {
    let current = true;
    api(`/projects/${project.id}/webhook`)
      .then((settings) => {
        if (!current) return;
        setWebhook(settings);
        setWebhookEnabled(settings.enabled);
        setBranches(settings.branches || "*");
        setSecretSet(settings.secretSet);
      })
      .catch((e) => {
        if (current)
          setWebhookError(e.message || "Unable to load the webhook settings.");
      });
    return () => {
      current = false;
    };
  }, [api, project.id]);

  async function generateSecret() {
    if (webhookBusy) return;
    setWebhookBusy(true);
    setWebhookError("");
    try {
      const generated = await api(`/projects/${project.id}/webhook/secret`, {
        method: "POST",
      });
      setSecret(generated.secret);
      setSecretSet(true);
    } catch (e) {
      setWebhookError(e.message || "Unable to create a webhook secret.");
    } finally {
      setWebhookBusy(false);
    }
  }

  async function revealSecret() {
    if (webhookBusy) return;
    setWebhookBusy(true);
    setWebhookError("");
    try {
      const shown = await api(`/projects/${project.id}/webhook/secret`);
      setSecret(shown.secret);
      setSecretSet(true);
    } catch (e) {
      setWebhookError(e.message || "Unable to read the webhook secret.");
    } finally {
      setWebhookBusy(false);
    }
  }

  async function saveWebhook(event) {
    event.preventDefault();
    if (webhookBusy) return;
    setWebhookBusy(true);
    setWebhookError("");
    try {
      // Enabling before the secret exists would leave a webhook GitHub cannot sign.
      if (webhookEnabled && !secretSet) {
        const generated = await api(`/projects/${project.id}/webhook/secret`, {
          method: "POST",
        });
        setSecret(generated.secret);
        setSecretSet(true);
      }
      const saved = await api(`/projects/${project.id}/webhook`, {
        method: "PUT",
        body: JSON.stringify({ enabled: webhookEnabled, branches }),
      });
      setWebhook(saved);
      setWebhookEnabled(saved.enabled);
      setBranches(saved.branches || "*");
      setSecretSet(saved.secretSet);
    } catch (e) {
      setWebhookError(e.message || "Unable to save the webhook settings.");
    } finally {
      setWebhookBusy(false);
    }
  }

  async function copyWebhookUrl() {
    if (!webhook?.url) return;
    try {
      await navigator.clipboard.writeText(webhook.url);
      setWebhookError("");
    } catch {
      setWebhookError("Copy the webhook URL from the field instead.");
    }
  }

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
      <Card className="project-card">
        <CardHeader>
          <CardTitle>Automatic builds</CardTitle>
        </CardHeader>
        <CardContent>
          <form
            id="webhook-form"
            className="settings-form"
            onSubmit={saveWebhook}
          >
            {webhookError && (
              <div className="error-message" role="alert">
                {webhookError}
              </div>
            )}
            <p className="file-help">
              A verified push from GitHub starts a build of the pushed commit.
              Only enable this for repositories you trust: until isolated build
              workers exist, the build runs on the cisys server.
            </p>
            <div className="form-field">
              <Label htmlFor="webhook-enabled">Push trigger</Label>
              <span className="private-choice">
                <input
                  id="webhook-enabled"
                  type="checkbox"
                  checked={webhookEnabled}
                  onChange={(e) => setWebhookEnabled(e.target.checked)}
                />
                <span>Build pushed commits automatically</span>
              </span>
            </div>
            <div className="form-field">
              <Label htmlFor="webhook-url">Payload URL</Label>
              <div style={{ display: "flex", gap: "0.5rem" }}>
                <Input
                  id="webhook-url"
                  readOnly
                  value={webhook?.url || ""}
                  placeholder="Loading…"
                />
                <Button
                  type="button"
                  variant="outline"
                  size="icon"
                  disabled={!webhook?.url}
                  onClick={copyWebhookUrl}
                  title="Copy payload URL"
                  aria-label="Copy payload URL"
                >
                  <Copy size={16} aria-hidden="true" />
                </Button>
              </div>
              <span className="file-help">
                In GitHub: Settings → Webhooks → Add webhook. Content type
                application/json, and select only the push event.
              </span>
            </div>
            <div className="form-field">
              <Label htmlFor="webhook-branches">Branches</Label>
              <Input
                id="webhook-branches"
                maxLength="1000"
                value={branches}
                onChange={(e) => setBranches(e.target.value)}
                placeholder="*"
              />
              <span className="file-help">
                Comma-separated branch globs, for example main,release/*. Use *
                for every branch.
              </span>
            </div>
            <div className="form-field">
              <Label htmlFor="webhook-secret">Secret</Label>
              {secret ? (
                <div style={{ display: "flex", gap: "0.5rem" }}>
                  <Input id="webhook-secret" readOnly value={secret} />
                  <Button
                    type="button"
                    variant="outline"
                    onClick={() => setSecret("")}
                  >
                    Hide
                  </Button>
                </div>
              ) : (
                <div style={{ display: "flex", gap: "0.5rem" }}>
                  <Button
                    type="button"
                    variant="outline"
                    disabled={!secretSet || webhookBusy}
                    onClick={revealSecret}
                  >
                    <KeyRound size={16} aria-hidden="true" /> Reveal
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    disabled={webhookBusy}
                    onClick={generateSecret}
                  >
                    <RefreshCw size={16} aria-hidden="true" />
                    {secretSet ? "Rotate" : "Generate"}
                  </Button>
                </div>
              )}
              <span className="file-help">
                Use this value as the webhook secret in GitHub. Rotating it
                breaks the existing webhook until GitHub is updated.
              </span>
            </div>
            {webhook?.lastBuild && (
              <p className="file-help">
                Last push build #{webhook.lastBuild.id}:{" "}
                {webhook.lastBuild.status} on {webhook.lastBuild.branch}
                {webhook.lastBuild.revision
                  ? ` (${webhook.lastBuild.revision.slice(0, 12)})`
                  : ""}
                .
              </p>
            )}
            <Button type="submit" disabled={webhookBusy}>
              {webhookBusy ? "Saving…" : "Save webhook"}
            </Button>
          </form>
        </CardContent>
      </Card>
    </main>
  );
}
