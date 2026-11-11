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
    api('/settings/retention').then(setDraft).catch((e) => setError(e.message));
  }, [api]);

  function change(key, value) { setDraft((d) => ({ ...d, [key]: value })); setNotice(''); }

  async function submit(e) {
    e.preventDefault(); setBusy(true); setError(''); setNotice('');
    try {
      const body = { maxAgeDays: Number(draft.maxAgeDays), maxBuilds: Number(draft.maxBuilds) };
      setDraft(await api('/settings/retention', { method: 'PUT', body: JSON.stringify(body) }));
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
      <Button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save'}</Button>
    </form>}
  </main>;
}
