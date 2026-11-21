/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.BuildStatisticsResource.BuildStatistics;
import ai.mnemosyne.cisys.BuildStatisticsResource.Failure;
import ai.mnemosyne.cisys.BuildStatisticsResource.Row;
import ai.mnemosyne.cisys.UserResource.ProjectRef;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import jakarta.enterprise.context.ApplicationScoped;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Renders {@link BuildStatistics} as a PDF of tables. */
@ApplicationScoped
public class BuildStatisticsPdf {
  private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
  private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
  private static final Font HEAD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
  private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 9);
  private static final Color HEAD_FILL = new Color(235, 235, 235);
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
  private static final List<String> COLUMNS =
      List.of("Builds", "Passed", "Failed", "Cancelled", "Active", "Success", "Avg duration");

  /** The PDF bytes. */
  public byte[] render(BuildStatistics s) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Document doc = new Document(PageSize.A4.rotate(), 36, 36, 36, 36);
    PdfWriter.getInstance(doc, out);
    doc.open();
    doc.add(new Paragraph("Build report", TITLE));
    doc.add(
        new Paragraph(
            scope(s) + " · " + period(s.period()) + " · " + TIME.format(s.generatedAt()), BODY));
    section(doc, "Totals", "", List.of(s.totals()));
    section(doc, "Projects", "Project", s.byProject());
    section(
        doc,
        "Triggers",
        "Trigger",
        s.byTrigger().stream().map(BuildStatisticsPdf::trigger).toList());
    if (!s.byBranch().isEmpty()) {
      section(doc, "Branches", "Branch", s.byBranch());
    }
    failures(doc, s.recentFailures());
    doc.close();
    return out.toByteArray();
  }

  private static String scope(BuildStatistics s) {
    if (s.projectId() == null) {
      return "All projects";
    }
    return s.projects().stream()
        .filter(p -> p.id().equals(s.projectId()))
        .map(ProjectRef::name)
        .findFirst()
        .orElse("");
  }

  static String period(String period) {
    return switch (period) {
      case "7d" -> "Last 7 days";
      case "30d" -> "Last 30 days";
      case "365d" -> "Last year";
      default -> "All time";
    };
  }

  private static Row trigger(Row r) {
    String label =
        switch (r.key()) {
          case "POLL" -> "Poll";
          case "PULL_REQUEST" -> "Pull request";
          default -> "Manual";
        };
    return new Row(
        r.key(),
        label,
        r.builds(),
        r.passed(),
        r.failed(),
        r.cancelled(),
        r.active(),
        r.successRate(),
        r.averageSeconds(),
        r.lastBuildAt());
  }

  private static void section(Document doc, String title, String first, List<Row> rows) {
    doc.add(heading(title));
    if (rows.isEmpty() || rows.getFirst().builds() == 0) {
      doc.add(new Paragraph("No builds", BODY));
      return;
    }
    boolean labelled = !first.isEmpty();
    PdfPTable t = new PdfPTable(COLUMNS.size() + (labelled ? 1 : 0));
    t.setWidthPercentage(100);
    if (labelled) {
      t.addCell(head(first));
    }
    COLUMNS.forEach(c -> t.addCell(head(c)));
    for (Row r : rows) {
      if (labelled) {
        t.addCell(cell(r.label().isEmpty() ? "—" : r.label(), Element.ALIGN_LEFT));
      }
      for (String v :
          List.of(
              Long.toString(r.builds()),
              Long.toString(r.passed()),
              Long.toString(r.failed()),
              Long.toString(r.cancelled()),
              Long.toString(r.active()),
              percent(r.successRate()),
              duration(r.averageSeconds()))) {
        t.addCell(cell(v, Element.ALIGN_RIGHT));
      }
    }
    doc.add(t);
  }

  private static void failures(Document doc, List<Failure> failures) {
    doc.add(heading("Recent failures"));
    if (failures.isEmpty()) {
      doc.add(new Paragraph("No builds", BODY));
      return;
    }
    PdfPTable t = new PdfPTable(new float[] {3, 1, 2, 1.2f, 2});
    t.setWidthPercentage(100);
    List.of("Project", "Build", "Branch", "Commit", "Finished").forEach(c -> t.addCell(head(c)));
    for (Failure f : failures) {
      t.addCell(cell(f.project(), Element.ALIGN_LEFT));
      t.addCell(cell("#" + f.number(), Element.ALIGN_RIGHT));
      t.addCell(cell(f.branch(), Element.ALIGN_LEFT));
      t.addCell(
          cell(
              f.revision() == null
                  ? null
                  : f.revision().substring(0, Math.min(7, f.revision().length())),
              Element.ALIGN_LEFT));
      t.addCell(cell(time(f.finishedAt()), Element.ALIGN_LEFT));
    }
    doc.add(t);
  }

  private static Paragraph heading(String text) {
    Paragraph p = new Paragraph(text, HEADING);
    p.setSpacingBefore(14);
    p.setSpacingAfter(6);
    return p;
  }

  private static PdfPCell head(String text) {
    PdfPCell c = new PdfPCell(new Phrase(text, HEAD));
    c.setBackgroundColor(HEAD_FILL);
    c.setPadding(4);
    return c;
  }

  private static PdfPCell cell(String text, int align) {
    PdfPCell c = new PdfPCell(new Phrase(text == null || text.isEmpty() ? "—" : text, BODY));
    c.setHorizontalAlignment(align);
    c.setPadding(4);
    return c;
  }

  static String percent(Double rate) {
    return rate == null ? "—" : String.format(Locale.ROOT, "%.0f%%", rate * 100);
  }

  static String duration(Double seconds) {
    if (seconds == null) {
      return "—";
    }
    long s = Math.round(seconds), m = s / 60;
    return m > 0 ? m + "m " + s % 60 + "s" : s + "s";
  }

  private static String time(Instant at) {
    return at == null ? null : TIME.format(at);
  }
}
