/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from 'react';
import { FolderGit2, LockKeyhole, LockKeyholeOpen, LogIn } from 'lucide-react';
import { Badge } from './components/ui/badge';
import { Button } from './components/ui/button';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from './components/ui/card';
import PageHeader from './components/layout/PageHeader';

/** Returns the project name for paths like /cisys/ (or /cisys), otherwise null. */
export function projectNameFromPath(pathname) {
  const match = /^\/([^/]+)\/?$/.exec(pathname);
  if (!match) return null;
  try { return decodeURIComponent(match[1]); } catch { return null; }
}

export function projectPath(name) {
  return `/${encodeURIComponent(name)}/`;
}

/**
 * Builds of one project, addressed by name. `load(path)` fetches JSON from the API, with or without a token.
 * The API answers 404 both for unknown projects and for private projects the visitor may not see,
 * so anonymous visitors are offered to sign in. Render it with `key={name}` so state resets per project.
 */
export default function ProjectBuilds({ name, load, onSignIn }) {
  // undefined while loading, null when not found.
  const [page, setPage] = useState(undefined);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    const fetchPage = async () => {
      try {
        const data = await load(`/project-pages/${encodeURIComponent(name)}`);
        if (active) { setPage(data); setError(''); }
      } catch (e) {
        if (!active) return;
        if (e.status === 404) setPage(null);
        else setError(e.message || 'Unable to load the project.');
      }
    };
    void fetchPage();
    const timer = setInterval(fetchPage, 5000);
    return () => { active = false; clearInterval(timer); };
  }, [name, load]);

  if (page === null) {
    return (
      <main id="project-builds" className="page-container">
        <Card className="project-card">
          <CardContent>
            <div className="empty-state">
              <FolderGit2 size={28} aria-hidden="true"/>
              <strong>Project not found</strong>
              {onSignIn ? <>
                <p>If {name} is a private project, sign in to see its builds.</p>
                <Button type="button" onClick={onSignIn}><LogIn aria-hidden="true"/> Sign in</Button>
              </> : <p>There is no project called {name}, or you do not have access to it.</p>}
            </div>
          </CardContent>
        </Card>
      </main>
    );
  }

  if (page === undefined) {
    return <main id="project-builds" className="page-container">{error
      ? <div className="error-message" role="alert">{error}</div>
      : <p className="loading-state" role="status">Loading project…</p>}</main>;
  }

  const { project, builds } = page;
  return (
    <main id="project-builds" className="page-container">
      <PageHeader title={project.name} actions={
        <Badge variant="outline">{project.privateRepository
          ? <LockKeyhole size={12} className="text-red-600 dark:text-red-400" aria-hidden="true"/>
          : <LockKeyholeOpen size={12} className="text-green-600 dark:text-green-400" aria-hidden="true"/>}
        {project.privateRepository ? 'Private' : 'Public'}</Badge>}/>
      {error && <div className="error-message" role="alert">{error}</div>}
      <Card className="project-card">
        <CardHeader>
          <CardTitle>Builds</CardTitle>
          <CardDescription className="project-url">{project.repositoryUrl}</CardDescription>
        </CardHeader>
        <CardContent>
          {builds.length === 0 ? <p>No builds yet.</p> : builds.map((b) => (
            <details key={b.id}>
              <summary>#{b.id} · {b.status} · {b.startedAt ? new Date(b.startedAt).toLocaleString() : 'Not started'} {b.revision ? `· ${b.revision.slice(0, 12)}` : ''}</summary>
              <dl className="break-words">
                <dt>Branch</dt>
                <dd>{b.branch ?? 'Unknown'}</dd>
                <dt>Image</dt>
                <dd>{b.image ?? 'Unknown'}</dd>
                <dt>Command</dt>
                <dd><pre className="whitespace-pre-wrap">{b.command ?? 'Unknown'}</pre></dd>
              </dl>
              <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{b.output}</pre>
            </details>
          ))}
        </CardContent>
      </Card>
    </main>
  );
}
