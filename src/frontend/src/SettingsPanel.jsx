/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from 'react';
import { Button } from './components/ui/button';
import { Input } from './components/ui/input';
import { Label } from './components/ui/label';
import { Card, CardHeader, CardTitle, CardContent } from './components/ui/card';
import PageHeader from './components/layout/PageHeader';

export default function SettingsPanel({ api }) {
  const [draft, setDraft] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    Promise.all([api('/settings/retention'), api('/settings/queue')])
      .then(([retention, queue]) => setDraft({ ...retention, ...queue })).catch((e) => setError(e.message));
  }, [api]);

  function change(key, value) { setDraft((d) => ({ ...d, [key]: value })); setNotice(''); }

  async function submit(e) {
    e.preventDefault(); setBusy(true); setError(''); setNotice('');
    try {
      const retention = { maxAgeDays: Number(draft.maxAgeDays), maxBuilds: Number(draft.maxBuilds) };
      const queue = { maxConcurrent: Number(draft.maxConcurrent), maxQueued: Number(draft.maxQueued) };
      const saved = await Promise.all([
        api('/settings/retention', { method: 'PUT', body: JSON.stringify(retention) }),
        api('/settings/queue', { method: 'PUT', body: JSON.stringify(queue) }),
      ]);
      setDraft({ ...saved[0], ...saved[1] });
      setNotice('Settings saved.');
    } catch (e) { setError(e.message); } finally { setBusy(false); }
  }

  return <main className="page-container">
    <PageHeader title="Settings"/>
    {error && <div className="error-message" role="alert">{error}</div>}
    {notice && <div className="success-message" role="status">{notice}</div>}
    {!draft ? !error && <p role="status">Loading settings…</p> : <form onSubmit={submit}>
      <Card className="project-card">
        <CardHeader><CardTitle>Retention</CardTitle></CardHeader>
        <CardContent>
          <div className="settings-form">
            <div className="form-field">
              <Label htmlFor="retention-days">Delete builds older than (days)</Label>
              <Input id="retention-days" type="number" min="0" max="3650" step="1" required value={draft.maxAgeDays} onChange={(e) => change('maxAgeDays', e.target.value)}/>
            </div>
            <div className="form-field">
              <Label htmlFor="retention-builds">Keep at most (builds per project)</Label>
              <Input id="retention-builds" type="number" min="0" max="10000" step="1" required value={draft.maxBuilds} onChange={(e) => change('maxBuilds', e.target.value)}/>
            </div>
          </div>
        </CardContent>
      </Card>
      <Card className="project-card">
        <CardHeader><CardTitle>Queue</CardTitle></CardHeader>
        <CardContent>
          <div className="settings-form">
            <div className="form-field">
              <Label htmlFor="queue-concurrent">Concurrent builds</Label>
              <Input id="queue-concurrent" type="number" min="1" max="16" step="1" required value={draft.maxConcurrent} onChange={(e) => change('maxConcurrent', e.target.value)}/>
            </div>
            <div className="form-field">
              <Label htmlFor="queue-queued">Queued builds</Label>
              <Input id="queue-queued" type="number" min="1" max="1000" step="1" required value={draft.maxQueued} onChange={(e) => change('maxQueued', e.target.value)}/>
            </div>
          </div>
        </CardContent>
      </Card>
      <Button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save'}</Button>
    </form>}
  </main>;
}
