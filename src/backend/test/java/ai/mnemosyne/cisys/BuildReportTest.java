/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest
class BuildReportTest {
  @Inject BuildRunner runner;

  @TempDir Path checkout;

  /** A report type whose run always fails, to check that a failure is only noted. */
  @ApplicationScoped
  static class FailingReport implements ReportType {
    @Override
    public String type() {
      return "failing";
    }

    @Override
    public Report run(Context context) {
      throw new IllegalStateException("boom");
    }
  }

  static final String WORKFLOW =
      """
      name: main
      container: alpine:3.23
      dependencies:
        - name: Java
          value: openjdk25-jdk
      jobs:
        - job: Test
          steps:
            - name: Test
              run: make test
      reports:
        list:
          - name: List
        failing:
          - name: Broken
        missing:
          - name: Nobody
      """;

  record Seeded(String name, Long buildId) {}

  private Seeded seed(boolean priv) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          String n = UUID.randomUUID().toString();
          Project p = new Project();
          p.name = "Reports-" + n;
          p.repositoryUrl = "https://github.com/octocat/reports-" + n + ".git";
          p.ownerSubject = "owner-id";
          p.privateRepository = priv;
          p.persist();
          Build b = new Build();
          b.projectId = p.id;
          b.number = Build.nextNumber(p.id);
          b.status = Build.Status.PASSED;
          b.branch = "main";
          b.requestedBy = "owner-id";
          b.persist();
          return new Seeded(p.name, b.id);
        });
  }

  @Test
  void reportsAreStoredInWorkflowOrderAndServedWithTheBuild() throws Exception {
    Files.writeString(checkout.resolve("README.md"), "hello");
    Files.createDirectories(checkout.resolve("src"));
    Files.writeString(checkout.resolve("src/main.c"), "int main;");
    Seeded s = seed(false);
    List<String> log = new ArrayList<>();

    runner.runReports(s.buildId(), Workflow.parse(WORKFLOW), checkout, log::add);

    assertEquals(
        List.of(
            "Report List done",
            "Report Broken failed: IllegalStateException",
            "Report Nobody skipped: no report type missing"),
        log);
    given()
        .get("/api/project-pages/" + s.name().toLowerCase() + "/builds/" + s.buildId() + "/reports")
        .then()
        .statusCode(200)
        .body("$", hasSize(1))
        .body("[0].type", equalTo("list"))
        .body("[0].name", equalTo("List"))
        .body("[0].format", equalTo("TEXT"))
        .body("[0].truncated", equalTo(false))
        .body("[0].content", containsString("Java: openjdk25-jdk"))
        .body("[0].content", containsString("  README.md\n"))
        .body("[0].content", containsString("  src/main.c\n"));
  }

  @Test
  void aWorkflowWithoutReportsStoresNothing() {
    Seeded s = seed(false);
    List<String> log = new ArrayList<>();
    runner.runReports(
        s.buildId(),
        Workflow.parse(WORKFLOW.substring(0, WORKFLOW.indexOf("reports:"))),
        checkout,
        log::add);
    assertTrue(log.isEmpty());
    assertEquals(
        0L,
        (long)
            QuarkusTransaction.requiringNew()
                .call(() -> BuildReport.count("buildId", s.buildId())));
  }

  @Test
  void anonymousCannotReadThePrivateProjectsReports() {
    Seeded s = seed(true);
    runner.runReports(s.buildId(), Workflow.parse(WORKFLOW), checkout, line -> {});
    given()
        .get("/api/project-pages/" + s.name().toLowerCase() + "/builds/" + s.buildId() + "/reports")
        .then()
        .statusCode(404);
  }

  @Test
  void listKeepsMoreThanTenThousandFiles() throws Exception {
    for (int d = 0; d < 12; d++) {
      Path dir = Files.createDirectories(checkout.resolve("d" + d));
      for (int f = 0; f < 1000; f++) {
        Files.createFile(dir.resolve("f" + f + ".c"));
      }
    }
    String content =
        new ListReport()
            .run(
                new ReportType.Context(
                    checkout, Workflow.parse(WORKFLOW), java.util.Map.of("name", "List")))
            .content();
    assertTrue(content.endsWith("\n12000 files\n"), content.substring(content.length() - 40));
    assertFalse(content.contains("more\n"));
    assertTrue(content.contains("  d9/f999.c\n"));
  }

  @Test
  void listSkipsGitAndSymbolicLinks() throws Exception {
    Files.writeString(checkout.resolve("kept.txt"), "x");
    Files.createDirectories(checkout.resolve(".git"));
    Files.writeString(checkout.resolve(".git/config"), "x");
    Path outside = Files.createTempFile("outside", ".txt");
    try {
      Files.createSymbolicLink(checkout.resolve("link.txt"), outside);
      String content =
          new ListReport()
              .run(
                  new ReportType.Context(
                      checkout, Workflow.parse(WORKFLOW), java.util.Map.of("name", "List")))
              .content();
      assertTrue(content.contains("  kept.txt\n"), content);
      assertFalse(content.contains(".git"), content);
      assertFalse(content.contains("link.txt"), content);
      assertTrue(content.contains("1 file\n"), content);
    } finally {
      Files.deleteIfExists(outside);
    }
  }
}
