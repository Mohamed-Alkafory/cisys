/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import { Save } from "lucide-react";
import { Button } from "./components/ui/button";
import { Input } from "./components/ui/input";
import { Label } from "./components/ui/label";
import { Textarea } from "./components/ui/textarea";
import { Card, CardContent } from "./components/ui/card";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "./components/ui/tabs";
import PageHeader from "./components/layout/PageHeader";
import BrandingPanel from "./BrandingPanel";

const SECTIONS = {
  retention: {
    title: "Retention",
    fields: [
      {
        key: "maxAgeDays",
        id: "retention-days",
        label: "Delete builds older than (days)",
        min: 0,
        max: 3650,
      },
      {
        key: "maxBuilds",
        id: "retention-builds",
        label: "Keep at most (builds per project)",
        min: 0,
        max: 10000,
      },
    ],
  },
  queue: {
    title: "Queue",
    fields: [
      {
        key: "maxConcurrent",
        id: "queue-concurrent",
        label: "Concurrent builds",
        min: 1,
        max: 16,
      },
      {
        key: "maxQueued",
        id: "queue-queued",
        label: "Queued builds",
        min: 1,
        max: 1000,
      },
    ],
  },
};

function SettingsTab({ api, section }) {
  const { title, fields } = SECTIONS[section];
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    api(`/settings/${section}`)
      .then(setDraft)
      .catch((e) => setError(e.message));
  }, [api, section]);

  function change(key, value) {
    setDraft((d) => ({ ...d, [key]: value }));
    setNotice("");
  }

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const body = Object.fromEntries(
        fields.map(({ key }) => [key, Number(draft[key])]),
      );
      setDraft(
        await api(`/settings/${section}`, {
          method: "PUT",
          body: JSON.stringify(body),
        }),
      );
      setNotice(`${title} settings saved.`);
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="project-card">
      <CardContent className="pt-6">
        {error && (
          <div className="error-message" role="alert">
            {error}
          </div>
        )}
        {notice && (
          <div className="success-message" role="status">
            {notice}
          </div>
        )}
        {!draft ? (
          !error && <p role="status">Loading {title.toLowerCase()} settings…</p>
        ) : (
          <form onSubmit={submit}>
            <div className="settings-form">
              {fields.map(({ key, id, label, min, max }) => (
                <div className="form-field" key={key}>
                  <Label htmlFor={id}>{label}</Label>
                  <Input
                    id={id}
                    type="number"
                    min={min}
                    max={max}
                    step="1"
                    required
                    value={draft[key]}
                    onChange={(e) => change(key, e.target.value)}
                  />
                </div>
              ))}
            </div>
            <div className="flex justify-end mt-4">
              <Button
                type="submit"
                size="icon"
                className="h-10 w-10"
                disabled={busy}
                title={`Save ${title.toLowerCase()} settings`}
                aria-label={`Save ${title.toLowerCase()} settings`}
              >
                <Save size={16} aria-hidden="true" />
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

function NetworkTab({ api }) {
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    api("/settings/network")
      .then((n) => setDraft(n.allowedHosts.join("\n")))
      .catch((e) => setError(e.message));
  }, [api]);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const allowedHosts = draft
        .split("\n")
        .map((s) => s.trim())
        .filter(Boolean);
      const saved = await api("/settings/network", {
        method: "PUT",
        body: JSON.stringify({ allowedHosts }),
      });
      setDraft(saved.allowedHosts.join("\n"));
      setNotice("Network settings saved.");
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card className="project-card">
      <CardContent className="pt-6">
        {error && (
          <div className="error-message" role="alert">
            {error}
          </div>
        )}
        {notice && (
          <div className="success-message" role="status">
            {notice}
          </div>
        )}
        {draft === null ? (
          !error && <p role="status">Loading network settings…</p>
        ) : (
          <form onSubmit={submit}>
            <div className="form-field">
              <Label htmlFor="network-hosts">Allowed hosts</Label>
              <Textarea
                id="network-hosts"
                rows={8}
                spellCheck={false}
                placeholder="registry.npmjs.org"
                value={draft}
                onChange={(e) => {
                  setDraft(e.target.value);
                  setNotice("");
                }}
              />
            </div>
            <div className="flex justify-end mt-4">
              <Button
                type="submit"
                size="icon"
                className="h-10 w-10"
                disabled={busy}
                title="Save network settings"
                aria-label="Save network settings"
              >
                <Save size={16} aria-hidden="true" />
              </Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  );
}

export default function SettingsPanel({ api, branding, saveBranding }) {
  return (
    <main className="page-container">
      <PageHeader title="Settings" />
      <Tabs defaultValue="retention" className="settings-tabs">
        <TabsList>
          {Object.entries(SECTIONS).map(([value, { title }]) => (
            <TabsTrigger key={value} value={value}>
              {title}
            </TabsTrigger>
          ))}
          <TabsTrigger value="network">Network</TabsTrigger>
          <TabsTrigger value="branding">Branding</TabsTrigger>
        </TabsList>
        {Object.keys(SECTIONS).map((value) => (
          <TabsContent
            key={value}
            value={value}
            forceMount
            className="data-[state=inactive]:hidden"
          >
            <SettingsTab api={api} section={value} />
          </TabsContent>
        ))}
        <TabsContent
          value="network"
          forceMount
          className="data-[state=inactive]:hidden"
        >
          <NetworkTab api={api} />
        </TabsContent>
        <TabsContent
          value="branding"
          forceMount
          className="data-[state=inactive]:hidden"
        >
          <BrandingPanel branding={branding} save={saveBranding} />
        </TabsContent>
      </Tabs>
    </main>
  );
}
