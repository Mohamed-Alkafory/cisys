/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import {
  Hammer,
  CheckCircle2,
  XCircle,
  Ban,
  Percent,
  Clock,
  FileDown,
} from "lucide-react";
import { Button } from "./components/ui/button";
import { Label } from "./components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "./components/ui/select";
import PageHeader from "./components/layout/PageHeader";
import { StatTile } from "./PublicStats";
import { buildPath, shortRevision, triggerLabel } from "./build-format";

const PERIODS = [
  ["7d", "Last 7 days"],
  ["30d", "Last 30 days"],
  ["365d", "Last year"],
  ["all", "All time"],
];
const ALL = "all";

/** The query string for the report endpoints. */
export function statisticsQuery(projectId, period) {
  const params = new URLSearchParams({ period });
  if (projectId !== ALL) params.set("projectId", projectId);
  return `?${params}`;
}

export function percent(rate) {
  return rate == null ? "—" : `${Math.round(rate * 100)}%`;
}

export function seconds(value) {
  if (value == null) return "—";
  const s = Math.round(value),
    m = Math.floor(s / 60);
  return m > 0 ? `${m}m ${s % 60}s` : `${s}s`;
}

function RowsTable({ title, first, rows, label = (r) => r.label }) {
  return (
    <section className="report-section">
      <h2 className="report-heading">{title}</h2>
      {rows.length === 0 ? (
        <p className="empty-state">No builds</p>
      ) : (
        <div className="builds-table-wrap">
          <table className="builds-table">
            <thead>
              <tr>
                <th scope="col">{first}</th>
                <th scope="col">Builds</th>
                <th scope="col">Passed</th>
                <th scope="col">Failed</th>
                <th scope="col">Cancelled</th>
                <th scope="col">Active</th>
                <th scope="col">Success</th>
                <th scope="col">Avg duration</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.key ?? ""}>
                  <td>{label(r) || "—"}</td>
                  <td>{r.builds}</td>
                  <td>{r.passed}</td>
                  <td>{r.failed}</td>
                  <td>{r.cancelled}</td>
                  <td>{r.active}</td>
                  <td>{percent(r.successRate)}</td>
                  <td>{seconds(r.averageSeconds)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

/** Build statistics across the projects the caller may report on, with a PDF export. */
export default function BuildStatistics({ load, onOpenBuild }) {
  const [projectId, setProjectId] = useState(ALL);
  const [period, setPeriod] = useState("30d");
  const [data, setData] = useState(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let current = true;
    load(`/reports/builds${statisticsQuery(projectId, period)}`)
      .then((d) => {
        if (!current) return;
        setError("");
        setData(d);
      })
      .catch((e) => current && setError(e.message));
    return () => {
      current = false;
    };
  }, [load, projectId, period]);

  async function exportPdf() {
    setError("");
    try {
      const response = await load(
        `/reports/builds/pdf${statisticsQuery(projectId, period)}`,
        { raw: true, headers: { Accept: "application/pdf" } },
      );
      const disposition = response.headers.get("Content-Disposition") || "";
      const name =
        /filename="([^"]+)"/.exec(disposition)?.[1] || "build-report.pdf";
      const url = URL.createObjectURL(await response.blob());
      const a = document.createElement("a");
      a.href = url;
      a.download = name;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (e) {
      setError(e.message);
    }
  }

  const totals = data?.totals;
  return (
    <main id="reports" className="page-container">
      <PageHeader
        title="Reports"
        actions={
          <Button
            type="button"
            size="icon"
            className="h-10 w-10"
            onClick={exportPdf}
            disabled={!data}
            title="Export PDF"
            aria-label="Export PDF"
          >
            <FileDown aria-hidden="true" />
          </Button>
        }
      />
      <div className="report-filters">
        <div className="form-field">
          <Label htmlFor="report-project">Project</Label>
          <Select value={String(projectId)} onValueChange={setProjectId}>
            <SelectTrigger id="report-project" className="h-10 w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>All projects</SelectItem>
              {(data?.projects ?? []).map((p) => (
                <SelectItem key={p.id} value={String(p.id)}>
                  {p.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="form-field">
          <Label htmlFor="report-period">Period</Label>
          <Select value={period} onValueChange={setPeriod}>
            <SelectTrigger id="report-period" className="h-10 w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {PERIODS.map(([value, label]) => (
                <SelectItem key={value} value={value}>
                  {label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>
      {error && (
        <div className="error-message" role="alert">
          {error}
        </div>
      )}
      {!data ? (
        !error && (
          <p className="loading-state" role="status">
            Loading reports…
          </p>
        )
      ) : (
        <>
          <div className="stats-grid">
            <StatTile icon={Hammer} label="Builds" value={totals.builds} />
            <StatTile
              icon={CheckCircle2}
              label="Passed"
              value={totals.passed}
            />
            <StatTile icon={XCircle} label="Failed" value={totals.failed} />
            <StatTile icon={Ban} label="Cancelled" value={totals.cancelled} />
            <StatTile
              icon={Percent}
              label="Success rate"
              value={percent(totals.successRate)}
            />
            <StatTile
              icon={Clock}
              label="Avg duration"
              value={seconds(totals.averageSeconds)}
            />
          </div>
          <RowsTable title="Projects" first="Project" rows={data.byProject} />
          <RowsTable
            title="Triggers"
            first="Trigger"
            rows={data.byTrigger}
            label={(r) => triggerLabel(r.key)}
          />
          {data.projectId != null && (
            <RowsTable title="Branches" first="Branch" rows={data.byBranch} />
          )}
          <section className="report-section">
            <h2 className="report-heading">Recent failures</h2>
            {data.recentFailures.length === 0 ? (
              <p className="empty-state">No builds</p>
            ) : (
              <div className="builds-table-wrap">
                <table className="builds-table">
                  <thead>
                    <tr>
                      <th scope="col">Project</th>
                      <th scope="col">Build</th>
                      <th scope="col">Branch</th>
                      <th scope="col">Commit</th>
                      <th scope="col">Finished</th>
                    </tr>
                  </thead>
                  <tbody>
                    {data.recentFailures.map((f) => (
                      <tr key={f.id}>
                        <td>{f.project}</td>
                        <td>
                          <a
                            className="build-link"
                            href={buildPath(f.project, f.branch, f.id)}
                            onClick={(event) => {
                              if (!onOpenBuild) return;
                              event.preventDefault();
                              onOpenBuild(f.project, f.branch || "", f.id);
                            }}
                          >
                            #{f.number}
                          </a>
                        </td>
                        <td>{f.branch || "—"}</td>
                        <td>{shortRevision(f.revision)}</td>
                        <td>
                          {f.finishedAt
                            ? new Date(f.finishedAt).toLocaleString()
                            : "—"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </main>
  );
}
