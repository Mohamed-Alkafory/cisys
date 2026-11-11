/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { ArrowLeft, FolderGit2, Pin, PinOff } from 'lucide-react';
import { Button } from './components/ui/button';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from './components/ui/card';
import PageHeader from './components/layout/PageHeader';
import BuildStatus from './BuildStatus';
import { BuildLogPanel } from './BuildLogView';
import { duration, startedAt } from './build-format';

/** Dedicated page for one build, including the expanded log. */
export default function ProjectBuild({ name, branch, buildId, load, onBack }) {
  // undefined while loading, null when not found.
  const [build, setBuild] = useState(undefined);
  const [error, setError] = useState('');
  const [pinning, setPinning] = useState(false);

  const loadBuild = useCallback(async () => {
    try {
      setBuild(await load(`/project-pages/${encodeURIComponent(name)}/builds/${buildId}`));
      setError('');
    } catch (e) {
      if (e.status === 404) setBuild(null);
      else setError(e.message || 'Unable to load the build.');
    }
  }, [load, name, buildId]);

  async function togglePin() {
    setPinning(true); setError('');
    try {
      await load(`/projects/${build.projectId}/builds/${build.id}/pin`, { method: build.pinned ? 'DELETE' : 'PUT' });
      await loadBuild();
    } catch (e) { setError(e.message || 'Unable to change the pin.'); }
    finally { setPinning(false); }
  }

  useEffect(() => {
    const initial = setTimeout(loadBuild, 0);
    const timer = setInterval(loadBuild, 5000);
    return () => { clearTimeout(initial); clearInterval(timer); };
  }, [loadBuild]);

  if (build === null) {
    return (
      <main id="project-build" className="page-container">
        <Card className="project-card">
          <CardContent>
            <div className="empty-state">
              <FolderGit2 size={28} aria-hidden="true"/>
              <strong>Build not found</strong>
              <p>Build #{buildId} does not exist for {name}, or you do not have access to it.</p>
              <Button type="button" variant="outline" onClick={onBack}><ArrowLeft aria-hidden="true"/> Back to builds</Button>
            </div>
          </CardContent>
        </Card>
      </main>
    );
  }

  if (build === undefined) {
    return <main id="project-build" className="page-container">{error
      ? <div className="error-message" role="alert">{error}</div>
      : <p className="loading-state" role="status">Loading build…</p>}</main>;
  }

  const finished = build.finishedAt ? new Date(build.finishedAt).toLocaleString() : 'Not finished';

  return (
    <main id="project-build" className="page-container">
      <PageHeader title={`Build #${build.id}`} actions={<>
        {build.canPin && <Button type="button" variant="outline" onClick={togglePin} disabled={pinning}>
          {build.pinned ? <><PinOff aria-hidden="true"/> Unpin</> : <><Pin aria-hidden="true"/> Pin</>}
        </Button>}
        <Button type="button" variant="outline" onClick={onBack}><ArrowLeft aria-hidden="true"/> Back to builds</Button>
      </>}/>
      {error && <div className="error-message" role="alert">{error}</div>}
      <Card className="project-card">
        <CardHeader>
          <CardTitle className="build-title">
            <BuildStatus status={build.status}/>
            <span>{build.status}</span>
          </CardTitle>
          <CardDescription>{branch ? `${name} · ${branch}` : name}</CardDescription>
        </CardHeader>
        <CardContent>
          <dl className="build-summary">
            <dt>Branch</dt>
            <dd>{build.branch || 'Unknown'}</dd>
            <dt>Commit</dt>
            <dd><code>{build.revision ?? 'Unknown'}</code></dd>
            <dt>Started</dt>
            <dd>{startedAt(build)}</dd>
            <dt>Finished</dt>
            <dd>{finished}</dd>
            <dt>Duration</dt>
            <dd>{duration(build)}</dd>
            <dt>Exit code</dt>
            <dd>{build.exitCode ?? '—'}</dd>
            <dt>Pinned</dt>
            <dd>{build.pinned ? 'Yes' : 'No'}</dd>
            <dt>Image</dt>
            <dd>{build.image ?? 'Unknown'}{build.imageDigest ? ` (${build.imageDigest})` : ''}</dd>
            <dt>Command</dt>
            <dd><pre className="whitespace-pre-wrap">{build.command ?? 'Unknown'}</pre></dd>
          </dl>
        </CardContent>
      </Card>
      <Card className="project-card">
        <CardHeader><CardTitle>Log</CardTitle></CardHeader>
        <CardContent>
          <BuildLogPanel api={load} logPath={`/project-pages/${encodeURIComponent(name)}/builds/${build.id}/log`}
            build={build} onRefresh={loadBuild} showMeta={false} scrollable/>
        </CardContent>
      </Card>
    </main>
  );
}
