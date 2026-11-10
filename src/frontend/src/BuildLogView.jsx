/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useState } from 'react';
import { Download, RefreshCw } from 'lucide-react';
import { Button } from './components/ui/button';

const INLINE_LIMIT = 2 * 1024 * 1024;

function duration(build) {
  if (!build.finishedAt) return 'running';
  if (!build.startedAt) return 'unknown';
  const seconds = Math.max(0, Math.round((new Date(build.finishedAt) - new Date(build.startedAt)) / 1000));
  const m = Math.floor(seconds / 60), s = seconds % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

export default function BuildLogView({ api, logPath, build, onRefresh }) {
  const [loaded, setLoaded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [text, setText] = useState('');
  const [more, setMore] = useState(false);
  const [error, setError] = useState('');
  const path = logPath;

  async function load() {
    setLoading(true); setError('');
    try {
      const response = await api(path, { raw: true });
      const reader = response.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let received = 0, out = '', cut = false;
      for (;;) {
        const { done, value } = await reader.read();
        if (done) { out += decoder.decode(); break; }
        const room = INLINE_LIMIT - received;
        if (value.length > room) {
          // No final decode, so a character cut at the limit is dropped instead of shown as a replacement character.
          out += decoder.decode(value.subarray(0, room), { stream: true });
          cut = true;
          await reader.cancel();
          break;
        }
        received += value.length;
        out += decoder.decode(value, { stream: true });
      }
      setText(out); setMore(cut); setLoaded(true);
    } catch (e) { setError(e.message); }
    finally { setLoading(false); }
  }

  async function download() {
    setError('');
    const name = `build-${build.id}.log`;
    try {
      if (window.showSaveFilePicker) {
        // Ask for the file while the click still counts as a user gesture.
        let handle;
        try { handle = await window.showSaveFilePicker({ suggestedName: name }); }
        catch (e) { if (e.name === 'AbortError') return; throw e; }
        const response = await api(path, { raw: true });
        await response.body.pipeTo(await handle.createWritable());
        return;
      }
      const response = await api(path, { raw: true });
      const url = URL.createObjectURL(await response.blob());
      const a = document.createElement('a');
      a.href = url; a.download = name; a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (e) { setError(e.message); }
  }

  async function refresh() { if (onRefresh) await onRefresh(); await load(); }

  return (
    <details className="build-row" onToggle={(e) => { if (e.currentTarget.open && !loaded && !loading) void load(); }}>
      <summary>
        #{build.id} · {build.status}{build.branch ? ` · ${build.branch}` : ''} · {build.startedAt ? new Date(build.startedAt).toLocaleString() : 'Not started'} · {duration(build)}
        {build.revision ? ` · ${build.revision.slice(0, 12)}` : ''}{build.exitCode != null ? ` · exit ${build.exitCode}` : ''}
      </summary>
      <div className="build-detail">
        <dl className="break-words">
          <dt>Branch</dt>
          <dd>{build.branch ?? 'Unknown'}</dd>
          <dt>Image</dt>
          <dd>{build.image ?? 'Unknown'}{build.imageDigest ? ` (${build.imageDigest})` : ''}</dd>
          <dt>Command</dt>
          <dd><pre className="whitespace-pre-wrap">{build.command ?? 'Unknown'}</pre></dd>
        </dl>
        {build.logTruncated && <p className="file-help" role="status">The stored output for this build is incomplete or was cut at the size limit.</p>}
        <div className="build-log-actions">
          {build.status === 'RUNNING' && <Button type="button" variant="outline" size="sm" onClick={refresh} disabled={loading}><RefreshCw/> Refresh</Button>}
          <Button type="button" variant="outline" size="sm" onClick={download}><Download/> Download log</Button>
        </div>
        {error && <p className="file-help" role="alert">{error}</p>}
        {loading && !loaded ? <p className="file-help">Loading log…</p> : <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{text}</pre>}
        {more && <p className="file-help">Showing the first 2 MiB; download the full log.</p>}
      </div>
    </details>
  );
}
