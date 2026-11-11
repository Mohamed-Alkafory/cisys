/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from 'react';
import { FolderGit2, GitBranch, LockKeyhole, LockKeyholeOpen, LogIn, Pin } from 'lucide-react';
import { Badge } from './components/ui/badge';
import { Button } from './components/ui/button';
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from './components/ui/card';
import PageHeader from './components/layout/PageHeader';
import BuildStatus from './BuildStatus';
import { branchFromSegment, buildPath, duration, shortRevision, startedAt } from './build-format';

/** Returns the project name for paths like /cisys/ (or /cisys), otherwise null. */
export function projectNameFromPath(pathname) {
  const match = /^\/([^/]+)\/?$/.exec(pathname);
  if (!match) return null;
  try { return decodeURIComponent(match[1]); } catch { return null; }
}

/** Returns { name, branch, buildId } for paths like /cisys/master/build/51, otherwise null. */
export function projectBuildFromPath(pathname) {
  const match = /^\/([^/]+)\/([^/]+)\/build\/(\d+)\/?$/.exec(pathname);
  if (!match) return null;
  try {
    return {
      name: decodeURIComponent(match[1]),
      branch: branchFromSegment(decodeURIComponent(match[2])),
      buildId: Number(match[3]),
    };
  } catch { return null; }
}

export function projectPath(name) {
  return `/${encodeURIComponent(name)}/`;
}

/**
 * Builds of one project, addressed by name. `load(path)` fetches JSON from the API, with or without a token.
 * The API answers 404 both for unknown projects and for private projects the visitor may not see,
 * so anonymous visitors are offered to sign in. Render it with `key={name}` so state resets per project.
 */
export default function ProjectBuilds({ name, load, onSignIn, onOpenBuild, initialBranch = '' }) {
  // undefined while loading, null when not found.
  const [page, setPage] = useState(undefined);
  const [error, setError] = useState('');
  const [branches, setBranches] = useState(null);
  const [branchesError, setBranchesError] = useState(false);
  const [selectedBranch, setSelectedBranch] = useState(initialBranch);
  const [buildsBranch, setBuildsBranch] = useState('');
  const projectId = page?.project?.id;
  // main is the default branch; older repositories still use master. There is no "all branches" view.
  const branchNames = branches || [];
  const defaultBranch = branchNames.includes('main') ? 'main'
    : branchNames.includes('master') ? 'master' : (branchNames[0] || '');
  const activeBranch = selectedBranch || defaultBranch;

  useEffect(() => {
    let active = true;
    const branchQuery = activeBranch ? `?branch=${encodeURIComponent(activeBranch)}` : '';
    const fetchPage = async () => {
      try {
        const data = await load(`/project-pages/${encodeURIComponent(name)}${branchQuery}`);
        if (active) { setPage(data); setBuildsBranch(activeBranch); setError(''); }
      } catch (e) {
        if (!active) return;
        if (e.status === 404) setPage(null);
        else setError(e.message || 'Unable to load the project.');
      }
    };
    void fetchPage();
    const timer = setInterval(fetchPage, 5000);
    return () => { active = false; clearInterval(timer); };
  }, [name, load, activeBranch]);

  // Branches are loaded once per project, not on every build refresh. Public projects read the
  // repository; private projects list the branches that already have builds.
  useEffect(() => {
    if (projectId == null) return undefined;
    let active = true;
    load(`/project-pages/${encodeURIComponent(name)}/branches`)
      .then((data) => {
        if (active) { setBranches(Array.isArray(data.branches) ? data.branches : []); setBranchesError(false); }
      })
      .catch(() => {
        if (active) { setBranches([]); setBranchesError(true); }
      });
    return () => { active = false; };
  }, [name, load, projectId]);

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
  // While the branches load, or while a branch-filtered fetch is in flight, do not show the previous
  // unfiltered builds. Otherwise a build from another branch flashes on screen and then disappears.
  const buildsReady = branches !== null && buildsBranch === activeBranch;
  const openBuild = (build) => onOpenBuild?.(project.name, build.branch || activeBranch, build.id);

  return (
    <main id="project-builds" className="page-container">
      <PageHeader title={project.name} actions={
        <Badge variant="outline">{project.privateRepository
          ? <LockKeyhole size={12} className="text-red-600 dark:text-red-400" aria-hidden="true"/>
          : <LockKeyholeOpen size={12} className="text-green-600 dark:text-green-400" aria-hidden="true"/>}
        {project.privateRepository ? 'Private' : 'Public'}</Badge>
      }/>
      {error && <div className="error-message" role="alert">{error}</div>}
      <div className="project-layout">
        <aside className="branch-panel" aria-label="Branches">
          <h2 className="branch-panel-title"><GitBranch size={14} aria-hidden="true"/> Branches</h2>
          {branches === null && !branchesError ? <p className="branch-panel-note">Loading branches…</p>
            : branchNames.length === 0 && !branchesError ? <p className="branch-panel-note">No branches found.</p>
            : (
              <ul className="branch-list">
                {branchNames.map((branch) => (
                  <li key={branch}>
                    <button type="button" className="branch-option" aria-current={activeBranch === branch ? 'page' : undefined}
                      onClick={() => setSelectedBranch(branch)}>{branch}</button>
                  </li>
                ))}
              </ul>
            )}
          {branchesError && <p className="branch-panel-note">Branches unavailable</p>}
        </aside>
        <Card className="project-card builds-panel">
          <CardHeader>
            <CardTitle>Builds</CardTitle>
            <CardDescription className="project-url">
              {activeBranch ? `${activeBranch} · ` : ''}newest first · {project.repositoryUrl}
            </CardDescription>
          </CardHeader>
          <CardContent>
            {!buildsReady ? (
              <p className="loading-state" role="status">Loading builds…</p>
            ) : builds.length === 0 ? (
              <p>{activeBranch ? `No builds for ${activeBranch} yet.` : 'No builds yet.'}</p>
            ) : (
              <div className="builds-table-wrap">
                <table className="builds-table">
                  <thead>
                    <tr>
                      <th scope="col"><span className="sr-only">Status</span></th>
                      <th scope="col">Build</th>
                      <th scope="col">Branch</th>
                      <th scope="col">Commit</th>
                      <th scope="col">Started</th>
                      <th scope="col">Duration</th>
                      <th scope="col">Exit</th>
                    </tr>
                  </thead>
                  <tbody>
                    {builds.map((b) => (
                      <tr key={b.id} className={onOpenBuild ? 'builds-row-link' : undefined}
                        onClick={onOpenBuild ? () => openBuild(b) : undefined}>
                        <td className="builds-status-cell"><BuildStatus status={b.status}/></td>
                        <td>
                          <a className="build-link" href={buildPath(project.name, b.branch || activeBranch, b.id)}
                            onClick={(event) => {
                              if (!onOpenBuild) return;
                              event.preventDefault();
                              event.stopPropagation();
                              openBuild(b);
                            }}>#{b.id}</a>
                          {b.pinned && <Pin className="build-pin" size={14} role="img" aria-label="Pinned"/>}
                        </td>
                        <td className="build-branch">{b.branch || '—'}</td>
                        <td><code className="build-commit" title={b.revision ?? undefined}>{shortRevision(b.revision)}</code></td>
                        <td className="build-meta">{startedAt(b)}</td>
                        <td className="build-meta">{duration(b)}</td>
                        <td className="build-meta">{b.exitCode ?? '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </main>
  );
}
