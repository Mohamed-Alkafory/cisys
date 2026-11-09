/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useState } from 'react';
import { Button } from './components/ui/button';
import { Input } from './components/ui/input';
import { Label } from './components/ui/label';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from './components/ui/card';
import PageHeader from './components/layout/PageHeader';

export default function BrandingPanel({ branding, save }) {
  const [draft, setDraft] = useState(branding);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  function change(key, value) { setDraft((d) => ({ ...d, [key]: value })); setNotice(''); }
  function image(key, file) {
    setError('');
    if (!file) return;
    if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 250000) { setError('Choose a PNG or JPEG image up to 250 KB.'); return; }
    const reader = new FileReader();
    reader.onload = () => change(key, reader.result);
    reader.onerror = () => setError('Unable to read the image.');
    reader.readAsDataURL(file);
  }
  async function submit(e) {
    e.preventDefault(); setBusy(true); setError(''); setNotice('');
    try { const result = await save(draft); setDraft(result); setNotice('Branding saved.'); }
    catch (err) { setError(err.message); } finally { setBusy(false); }
  }
  return <main className="page-container">
    <PageHeader title="Branding" subtitle="Customize the appearance of your Cisys installation."/>
    {error && <div className="error-message" role="alert">{error}</div>}
    {notice && <div className="success-message" role="status">{notice}</div>}
    <Card className="project-card"><CardHeader><CardTitle>Installation branding</CardTitle><CardDescription>Changes apply to everyone after they refresh or sign in.</CardDescription></CardHeader><CardContent>
      <form className="branding-form" onSubmit={submit}>
        <div className="form-field"><Label htmlFor="brand-name">Display name</Label><Input id="brand-name" required maxLength="80" value={draft.name} onChange={(e) => change('name', e.target.value)}/></div>
        <div className="form-field"><Label htmlFor="fontFamily">Font</Label><select id="fontFamily" className="font-select" value={draft.fontFamily || 'red-hat'} onChange={(e) => change('fontFamily', e.target.value)}><option value="red-hat">Red Hat Text</option><option value="system">System sans-serif</option></select></div>
        <div className="color-fields">{[['headerColor','Header and footer'],['headingColor','Heading'],['buttonColor','Buttons']].map(([key, label]) => <div className="form-field" key={key}><Label htmlFor={key}>{label} color</Label><div className="color-control"><input id={key} type="color" value={draft[key]} onChange={(e) => change(key, e.target.value)}/><span>{draft[key]}</span></div></div>)}</div>
        { [['logo','Logo'],['background','Background image']].map(([key, label]) => <div className="form-field" key={key}><Label htmlFor={key}>{label}</Label><input id={key} type="file" accept="image/png,image/jpeg" onChange={(e) => image(key, e.target.files?.[0])}/><span className="file-help">PNG or JPEG, up to 250 KB.</span>{draft[key] && <div className="image-preview"><img src={draft[key]} alt={`${label} preview`}/><button type="button" onClick={() => change(key, null)}>Remove {label.toLowerCase()}</button></div>}</div>)}
        <div><Button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save branding'}</Button></div>
      </form>
    </CardContent></Card>
  </main>;
}
