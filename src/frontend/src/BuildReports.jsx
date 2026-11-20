/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

import React, { useEffect, useState } from "react";
import { Card, CardContent, CardHeader, CardTitle } from "./components/ui/card";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "./components/ui/tabs";

/**
 * The reports the build's workflow asked for, one tab each. Text is shown as is; HTML goes in a
 * frame with an empty sandbox, so it cannot run scripts or reach the page.
 */
export default function BuildReports({ name, buildId, load }) {
  const [reports, setReports] = useState([]);
  const [error, setError] = useState("");

  useEffect(() => {
    let live = true;
    load(`/project-pages/${encodeURIComponent(name)}/builds/${buildId}/reports`)
      .then((r) => live && setReports(Array.isArray(r) ? r : []))
      .catch(
        (e) => live && setError(e.message || "Unable to load the reports."),
      );
    return () => {
      live = false;
    };
  }, [load, name, buildId]);

  if (error) {
    return <p role="alert">{error}</p>;
  }
  if (reports.length === 0) {
    return null;
  }
  return (
    <Card className="project-card">
      <CardHeader>
        <CardTitle>Reports</CardTitle>
      </CardHeader>
      <CardContent>
        <Tabs defaultValue={reports[0].name}>
          <TabsList>
            {reports.map((r) => (
              <TabsTrigger key={r.name} value={r.name}>
                {r.name}
              </TabsTrigger>
            ))}
          </TabsList>
          {reports.map((r) => (
            <TabsContent key={r.name} value={r.name}>
              {r.format === "HTML" ? (
                <iframe
                  title={r.name}
                  sandbox=""
                  srcDoc={r.content}
                  style={{ width: "100%", minHeight: "32rem", border: 0 }}
                />
              ) : (
                <pre className="build-report">{r.content}</pre>
              )}
              {r.truncated && <p>The report was cut at its size limit.</p>}
            </TabsContent>
          ))}
        </Tabs>
      </CardContent>
    </Card>
  );
}
