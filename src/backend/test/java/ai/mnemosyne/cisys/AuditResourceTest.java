/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TeamTestProfile.class)
class AuditResourceTest {
  private static final String PATH = "/api/audit";
  private static final String JWT =
      "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";

  @BeforeEach
  void setUp() {
    clean();
    TestUserDirectory.add("admin-id", "admin", "admin");
    TestUserDirectory.add("lead-id", "lead", "lead");
    TestUserDirectory.add("member-id", "member", "member");
  }

  @AfterEach
  void tearDown() {
    removeFailingAuditTrigger();
    clean();
    TestUserDirectory.reset();
  }

  private static void clean() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          AuditEvent.deleteAll();
          NetworkHost.deleteAll();
          Article.deleteAll();
          Build.deleteAll();
          ProjectMember.deleteAll();
          Project.deleteAll();
          Settings.deleteAll();
        });
  }

  private static void installFailingAuditTrigger() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Panache.getEntityManager()
              .createNativeQuery(
                  "CREATE OR REPLACE FUNCTION fail_audit_insert() RETURNS trigger AS $$ "
                      + "BEGIN RAISE EXCEPTION 'audit insert disabled for test'; END; "
                      + "$$ LANGUAGE plpgsql")
              .executeUpdate();
          Panache.getEntityManager()
              .createNativeQuery("DROP TRIGGER IF EXISTS fail_audit_insert ON auditevent")
              .executeUpdate();
          Panache.getEntityManager()
              .createNativeQuery(
                  "CREATE TRIGGER fail_audit_insert BEFORE INSERT ON auditevent "
                      + "FOR EACH ROW EXECUTE FUNCTION fail_audit_insert()")
              .executeUpdate();
        });
  }

  private static void removeFailingAuditTrigger() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Panache.getEntityManager()
              .createNativeQuery("DROP TRIGGER IF EXISTS fail_audit_insert ON auditevent")
              .executeUpdate();
          Panache.getEntityManager()
              .createNativeQuery("DROP FUNCTION IF EXISTS fail_audit_insert()")
              .executeUpdate();
        });
  }

  private static Long event(
      String action,
      AuditOutcome outcome,
      String actorSubject,
      String actorUsername,
      Long projectId,
      String projectName,
      AuditTargetType targetType,
      String targetId,
      String targetName,
      String summary,
      Instant occurredAt,
      Map<String, Object> details) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          AuditEvent e = new AuditEvent();
          e.occurredAt = occurredAt;
          e.action = action;
          e.outcome = outcome;
          e.actorSubject = actorSubject;
          e.actorUsername = actorUsername;
          e.actorRoles = "admin";
          e.projectId = projectId;
          e.projectName = projectName;
          e.targetType = targetType;
          e.targetId = targetId;
          e.targetName = targetName;
          e.summary = summary;
          e.details = details;
          e.persist();
          return e.id;
        });
  }

  private static AuditEvent find(String action, AuditOutcome outcome) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            AuditEvent.<AuditEvent>find(
                    "action = ?1 and outcome = ?2 order by id desc", action, outcome)
                .firstResult());
  }

  private static long count(String action) {
    return QuarkusTransaction.requiringNew().call(() -> AuditEvent.count("action = ?1", action));
  }

  private static Long project(String owner) {
    return project(owner, "audit-" + UUID.randomUUID());
  }

  private static Long project(String owner, String name) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          String n = UUID.randomUUID().toString();
          p.name = name;
          p.repositoryUrl = "https://github.com/octocat/audit-" + n + ".git";
          p.ownerSubject = owner;
          p.privateRepository = false;
          p.persist();
          ProjectMember m = new ProjectMember();
          m.projectId = p.id;
          m.subject = owner;
          m.role = ProjectMember.TeamRole.LEAD;
          m.createdAt = Instant.now();
          m.persist();
          return p.id;
        });
  }

  private static Long build(Long projectId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Build b = new Build();
          b.projectId = projectId;
          b.number = Build.nextNumber(projectId);
          b.requestedBy = "lead-id";
          b.status = Build.Status.PASSED;
          b.branch = "main";
          b.image = "alpine:3.23";
          b.exitCode = 0;
          b.startedAt = Instant.now();
          b.persist();
          return b.id;
        });
  }

  @Test
  void anonymousCannotReadTheAuditLog() {
    given().get(PATH).then().statusCode(401);
    given().get(PATH + "/1").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "lead-id"),
        @Claim(key = "preferred_username", value = "lead")
      })
  void nonAdminsCannotReadTheAuditLog() {
    given().get(PATH).then().statusCode(403);
    given().get(PATH + "/1").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void listsNewestFirstWithPaginationAndTotal() {
    Instant now = Instant.now();
    event(
        "project.create",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        7L,
        "alpha",
        AuditTargetType.PROJECT,
        "7",
        "alpha",
        "Created project alpha",
        now.minus(3, ChronoUnit.MINUTES),
        Map.of());
    event(
        "project.update",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        7L,
        "alpha",
        AuditTargetType.PROJECT,
        "7",
        "alpha",
        "Updated project alpha",
        now.minus(2, ChronoUnit.MINUTES),
        Map.of("changed", java.util.List.of("name")));
    event(
        "project.delete",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        7L,
        "alpha",
        AuditTargetType.PROJECT,
        "7",
        "alpha",
        "Deleted project alpha",
        now.minus(1, ChronoUnit.MINUTES),
        Map.of());

    given()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(3))
        .body("events", hasSize(3))
        .body(
            "events.summary",
            contains("Deleted project alpha", "Updated project alpha", "Created project alpha"));

    given()
        .queryParam("first", 1)
        .queryParam("max", 1)
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(3))
        .body("events", hasSize(1))
        .body("events[0].summary", equalTo("Updated project alpha"));

    given().queryParam("max", 100000).get(PATH).then().statusCode(200).body("events", hasSize(3));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void pagesByRowOffsetNotByPageNumber() {
    Instant base = Instant.parse("2026-01-02T03:00:00Z");
    for (int i = 0; i < 7; i++) {
      event(
          "probe.page",
          AuditOutcome.SUCCESS,
          "admin-id",
          "admin",
          null,
          null,
          AuditTargetType.SETTINGS,
          null,
          null,
          "event " + i,
          base.plusSeconds(i),
          Map.of());
    }
    List<String> newestFirst =
        List.of("event 6", "event 5", "event 4", "event 3", "event 2", "event 1", "event 0");

    assertEquals(newestFirst.subList(0, 3), summaries(0, 3));
    assertEquals(newestFirst.subList(3, 6), summaries(3, 3));
    assertEquals(newestFirst.subList(6, 7), summaries(6, 3));
    assertEquals(List.of(), summaries(9, 3));
    assertEquals(newestFirst.subList(2, 5), summaries(2, 3));

    List<String> paged = new ArrayList<>();
    for (int first = 0; first < newestFirst.size(); first += 3) {
      paged.addAll(summaries(first, 3));
    }
    assertEquals(newestFirst, paged);
    assertEquals((long) newestFirst.size(), paged.stream().distinct().count());
  }

  private static List<String> summaries(int first, int max) {
    return given()
        .queryParam("first", first)
        .queryParam("max", max)
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(7))
        .extract()
        .jsonPath()
        .getList("events.summary", String.class);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void filtersByEveryDocumentedField() {
    Instant now = Instant.now();
    event(
        "project.create",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        7L,
        "alpha",
        AuditTargetType.PROJECT,
        "7",
        "alpha",
        "Created project alpha",
        now.minus(3, ChronoUnit.MINUTES),
        Map.of());
    event(
        "build.pin",
        AuditOutcome.SUCCESS,
        "lead-id",
        "lead",
        8L,
        "beta",
        AuditTargetType.BUILD,
        "42",
        "#42",
        "Pinned build #42",
        now.minus(2, ChronoUnit.MINUTES),
        Map.of());
    event(
        "project.create",
        AuditOutcome.DENIED,
        "member-id",
        "member",
        null,
        null,
        AuditTargetType.PROJECT,
        null,
        null,
        "Create project",
        now.minus(1, ChronoUnit.MINUTES),
        Map.of("status", 403));

    given()
        .queryParam("action", "project.create")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(2));
    given()
        .queryParam("outcome", "success")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(2));
    given()
        .queryParam("outcome", "denied")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("events[0].actorSubject", equalTo("member-id"));
    given()
        .queryParam("actor", "lead")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("events[0].details", equalTo(Map.of()));
    given()
        .queryParam("actor", "member-id")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1));
    given().queryParam("project", 7).get(PATH).then().statusCode(200).body("total", equalTo(1));
    given()
        .queryParam("targetType", "build")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1));
    given().queryParam("target", "#42").get(PATH).then().statusCode(200).body("total", equalTo(1));
    given().queryParam("search", "beta").get(PATH).then().statusCode(200).body("total", equalTo(1));
    given()
        .queryParam("search", "MEMBER")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1));
    given()
        .queryParam("from", now.minus(150, ChronoUnit.SECONDS).toString())
        .queryParam("to", now.minus(90, ChronoUnit.SECONDS).toString())
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1))
        .body("events[0].action", equalTo("build.pin"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void rejectsInvalidFilters() {
    given().queryParam("outcome", "maybe").get(PATH).then().statusCode(400);
    given().queryParam("targetType", "planet").get(PATH).then().statusCode(400);
    given().queryParam("from", "yesterday").get(PATH).then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void returnsOneEventAndHidesMissingOnes() {
    Long id =
        event(
            "settings.general.update",
            AuditOutcome.SUCCESS,
            "admin-id",
            "admin",
            null,
            null,
            AuditTargetType.SETTINGS,
            "1",
            "general",
            "Updated the general settings",
            Instant.now(),
            Map.of("articleEditors", "ADMIN"));
    given()
        .get(PATH + "/" + id)
        .then()
        .statusCode(200)
        .body("id", equalTo(id.intValue()))
        .body("action", equalTo("settings.general.update"))
        .body("outcome", equalTo("SUCCESS"))
        .body("actorRoles", contains("admin"))
        .body("details.articleEditors", equalTo("ADMIN"));
    given().get(PATH + "/999999999").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsProjectCreationOnceWithStableSubjectAndNoCredential() {
    String name = "created-" + UUID.randomUUID();
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + name
                + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                + name
                + ".git\"}")
        .post("/api/projects")
        .then()
        .statusCode(201);

    AuditEvent created = find("project.create", AuditOutcome.SUCCESS);
    assertEquals("admin-id", created.actorSubject);
    assertEquals("admin", created.actorUsername);
    assertEquals("PROJECT", created.targetType.name());
    assertEquals(name, created.projectName);
    assertEquals(name, created.targetName);
    assertFalse(created.details.containsKey("credentialRef"));
    assertFalse(created.details.containsKey("credential"));
    assertEquals(1, count("project.create"));
    assertEquals(1, count("project.member.add"));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "member-id"),
        @Claim(key = "preferred_username", value = "member")
      })
  void recordsDeniedCallsWithoutChangingTheResponse() {
    given()
        .contentType("application/json")
        .header("X-Request-Id", "request-123")
        .body("{\"name\":\"denied\",\"repositoryUrl\":\"https://github.com/repo.git\"}")
        .post("/api/projects")
        .then()
        .statusCode(403);

    AuditEvent denied = find("project.create", AuditOutcome.DENIED);
    assertEquals("member-id", denied.actorSubject);
    assertEquals("member", denied.actorUsername);
    assertEquals("POST", denied.httpMethod);
    assertEquals("/api/projects", denied.path);
    assertEquals("request-123", denied.requestId);
    assertEquals(403, denied.statusCode);
    assertEquals(403, denied.details.get("status"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsConflictsAsFailures() {
    String host = "conflict-" + UUID.randomUUID() + ".example";
    given()
        .contentType("application/json")
        .body("{\"name\":\"one\",\"host\":\"" + host + "\"}")
        .post("/api/network-hosts")
        .then()
        .statusCode(201);
    given()
        .contentType("application/json")
        .body("{\"name\":\"two\",\"host\":\"" + host + "\"}")
        .post("/api/network-hosts")
        .then()
        .statusCode(409);

    AuditEvent failure = find("network.host.create", AuditOutcome.FAILURE);
    assertEquals("admin-id", failure.actorSubject);
    assertEquals(409, failure.statusCode);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void keepsTheProjectSnapshotAfterDeletion() {
    String name = "deleted-" + UUID.randomUUID();
    Long id =
        given()
            .contentType("application/json")
            .body(
                "{\"name\":\""
                    + name
                    + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                    + name
                    + ".git\"}")
            .post("/api/projects")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    given().delete("/api/projects/" + id).then().statusCode(204);

    AuditEvent deleted = find("project.delete", AuditOutcome.SUCCESS);
    assertEquals(id, deleted.projectId);
    assertEquals(name, deleted.projectName);
    assertEquals(String.valueOf(id), deleted.targetId);
    assertEquals(name, deleted.targetName);
    assertNull(QuarkusTransaction.requiringNew().call(() -> Project.findById(id)));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsArticleLifecycle() {
    String title = "Article " + UUID.randomUUID();
    Long id =
        given()
            .contentType("application/json")
            .body("{\"title\":\"" + title + "\",\"body\":\"# Hello\"}")
            .post("/api/articles")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");
    given()
        .contentType("application/json")
        .body("{\"title\":\"" + title + " v2\",\"body\":\"# Hello again\"}")
        .put("/api/articles/" + id)
        .then()
        .statusCode(200);
    given().delete("/api/articles/" + id).then().statusCode(204);

    assertEquals(1, count("article.create"));
    assertEquals(1, count("article.update"));
    assertEquals(1, count("article.delete"));
    AuditEvent updated = find("article.update", AuditOutcome.SUCCESS);
    assertEquals(java.util.List.of("title", "body"), updated.details.get("changed"));
    AuditEvent deleted = find("article.delete", AuditOutcome.SUCCESS);
    assertEquals(title + " v2", deleted.targetName);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "lead-id"),
        @Claim(key = "preferred_username", value = "lead")
      })
  void recordsBuildPinAndUnpin() {
    Long projectId = project("lead-id");
    Long buildId = build(projectId);
    given()
        .put("/api/projects/" + projectId + "/builds/" + buildId + "/pin")
        .then()
        .statusCode(200);
    given()
        .delete("/api/projects/" + projectId + "/builds/" + buildId + "/pin")
        .then()
        .statusCode(200);

    assertEquals(1, count("build.pin"));
    assertEquals(1, count("build.unpin"));
    AuditEvent pinned = find("build.pin", AuditOutcome.SUCCESS);
    assertEquals("lead-id", pinned.actorSubject);
    assertEquals(projectId, pinned.projectId);
    assertEquals(String.valueOf(buildId), pinned.targetId);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "member-id"),
        @Claim(key = "preferred_username", value = "member")
      })
  void recordsDeniedBuildActions() {
    Long projectId = project("lead-id");
    Long buildId = build(projectId);
    given()
        .put("/api/projects/" + projectId + "/builds/" + buildId + "/pin")
        .then()
        .statusCode(403);

    AuditEvent denied = find("build.pin", AuditOutcome.DENIED);
    assertEquals("member-id", denied.actorSubject);
    assertEquals(projectId, denied.projectId);
    assertEquals(String.valueOf(buildId), denied.targetId);
    assertEquals(403, denied.statusCode);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void imageRemovalSurvivesAnAuditInsertFailure() {
    String image = "audit-" + UUID.randomUUID() + ":1";
    QuarkusTransaction.requiringNew()
        .run(() -> {
          AllowedImage allowed = new AllowedImage();
          allowed.name = image;
          allowed.persist();
        });
    BuildRunner fake =
        new BuildRunner() {
          @Override
          Optional<String> remove(String name) {
            return Optional.empty();
          }

          @Override
          List<LocalImage> images() {
            return List.of();
          }
        };
    QuarkusMock.installMockForType(fake, BuildRunner.class);
    List<String> warnings = new ArrayList<>();
    Logger logger = Logger.getLogger(AuditService.class.getName());
    Handler handler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            warnings.add(record.getMessage());
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      installFailingAuditTrigger();
      given().delete("/api/images/local?name=" + image).then().statusCode(200);
      assertEquals(0, count("image.remove"));
      assertEquals(
          0, QuarkusTransaction.requiringNew().call(() -> AllowedImage.count("name = ?1", image)));
      assertTrue(
          warnings.stream()
              .anyMatch(m -> m != null && m.contains("Could not write an audit event")));
    } finally {
      logger.removeHandler(handler);
      QuarkusTransaction.requiringNew().run(() -> AllowedImage.deleteById(image));
    }
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void redactsAWholeJwtInAnArticleTitle() {
    given()
        .contentType("application/json")
        .body("{\"title\":\"" + JWT + "\",\"body\":\"# Hello\"}")
        .post("/api/articles")
        .then()
        .statusCode(201);

    AuditEvent created = find("article.create", AuditOutcome.SUCCESS);
    assertFalse(created.summary.contains(JWT));
    assertFalse(created.targetName.contains(JWT));
    assertEquals("[redacted]", created.targetName);
    assertEquals("[redacted]", created.details.get("title"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void redactsAnEmbeddedJwtInProse() {
    String title = "Token " + JWT + " pasted here";
    given()
        .contentType("application/json")
        .body("{\"title\":\"" + title + "\",\"body\":\"# Hello\"}")
        .post("/api/articles")
        .then()
        .statusCode(201);

    AuditEvent created = find("article.create", AuditOutcome.SUCCESS);
    assertFalse(created.summary.contains(JWT));
    assertFalse(created.targetName.contains(JWT));
    assertTrue(created.summary.contains("[redacted]"));
    assertTrue(created.targetName.contains("[redacted]"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "lead-id"),
        @Claim(key = "preferred_username", value = "lead")
      })
  void redactsTheProjectNameSnapshot() {
    String jwt = "aaaaaaaaaaaaaaaa.bbbbbbbb.cccccccc";
    Long projectId = project("lead-id", "p-" + jwt);
    Long buildId = build(projectId);
    given()
        .put("/api/projects/" + projectId + "/builds/" + buildId + "/pin")
        .then()
        .statusCode(200);

    AuditEvent pinned = find("build.pin", AuditOutcome.SUCCESS);
    assertFalse(pinned.projectName.contains(jwt));
    assertTrue(pinned.projectName.contains("[redacted]"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = JWT)
      })
  void redactsTheActorUsername() {
    given()
        .contentType("application/json")
        .body("{\"title\":\"Actor note\",\"body\":\"# Hello\"}")
        .post("/api/articles")
        .then()
        .statusCode(201);

    AuditEvent created = find("article.create", AuditOutcome.SUCCESS);
    assertEquals("[redacted]", created.actorUsername);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsProjectUpdateBeforeAndAfterTheChange() {
    String before = "before-" + UUID.randomUUID();
    String after = "after-" + UUID.randomUUID();
    Long id =
        given()
            .contentType("application/json")
            .body(
                "{\"name\":\""
                    + before
                    + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                    + before
                    + ".git\"}")
            .post("/api/projects")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getLong("id");

    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + after
                + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                + after
                + ".git\",\"privateRepository\":true}")
        .put("/api/projects/" + id)
        .then()
        .statusCode(200);

    AuditEvent updated = find("project.update", AuditOutcome.SUCCESS);
    assertEquals(before, updated.details.get("nameBefore"));
    assertEquals(after, updated.details.get("nameAfter"));
    assertEquals(
        "https://github.com/octocat/" + before, updated.details.get("repositoryUrlBefore"));
    assertEquals("https://github.com/octocat/" + after, updated.details.get("repositoryUrlAfter"));
    assertEquals(false, updated.details.get("privateRepositoryBefore"));
    assertEquals(true, updated.details.get("privateRepositoryAfter"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsSettingsBeforeAndAfterTheChange() {
    seedSettings();

    given()
        .contentType("application/json")
        .body("{\"articleEditors\":\"MEMBER\"}")
        .put("/api/settings/general")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"maxAgeDays\":7,\"maxBuilds\":9}")
        .put("/api/settings/retention")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"maxConcurrent\":5,\"maxQueued\":6,\"timeoutMinutes\":7}")
        .put("/api/settings/builds")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"maxSizeMib\":200,\"maxFiles\":2000}")
        .put("/api/settings/checkout")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"maxMemoryMib\":1024,\"maxCpus\":4,\"maxPids\":256}")
        .put("/api/settings/performance")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body(
            "{\"enabled\":false,\"granularity\":\"TIP\",\"scope\":\"MAIN_ONLY\","
                + "\"intervalSeconds\":120,\"mirrorManualBuilds\":true}")
        .put("/api/settings/polling")
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"enabled\":true}")
        .put("/api/settings/pull-requests")
        .then()
        .statusCode(200);

    AuditEvent general = find("settings.general.update", AuditOutcome.SUCCESS);
    assertEquals("ADMIN", general.details.get("articleEditorsBefore"));
    assertEquals("MEMBER", general.details.get("articleEditorsAfter"));

    AuditEvent retention = find("settings.retention.update", AuditOutcome.SUCCESS);
    assertEquals(30, retention.details.get("maxAgeDaysBefore"));
    assertEquals(7, retention.details.get("maxAgeDaysAfter"));

    AuditEvent builds = find("settings.builds.update", AuditOutcome.SUCCESS);
    assertEquals(2, builds.details.get("maxConcurrentBefore"));
    assertEquals(5, builds.details.get("maxConcurrentAfter"));

    AuditEvent checkout = find("settings.checkout.update", AuditOutcome.SUCCESS);
    assertEquals(100, checkout.details.get("maxSizeMibBefore"));
    assertEquals(200, checkout.details.get("maxSizeMibAfter"));

    AuditEvent performance = find("settings.performance.update", AuditOutcome.SUCCESS);
    assertEquals(512, performance.details.get("maxMemoryMibBefore"));
    assertEquals(1024, performance.details.get("maxMemoryMibAfter"));

    AuditEvent polling = find("settings.polling.update", AuditOutcome.SUCCESS);
    assertEquals(true, polling.details.get("enabledBefore"));
    assertEquals(false, polling.details.get("enabledAfter"));

    AuditEvent pullRequests = find("settings.pullrequests.update", AuditOutcome.SUCCESS);
    assertEquals(false, pullRequests.details.get("enabledBefore"));
    assertEquals(true, pullRequests.details.get("enabledAfter"));
  }

  private static void seedSettings() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Settings s = new Settings();
          s.retentionDays = 30;
          s.retentionMaxBuilds = 50;
          s.maxConcurrentBuilds = 2;
          s.maxQueuedBuilds = 3;
          s.buildTimeoutMinutes = 4;
          s.maxCheckoutMib = 100;
          s.maxCheckoutFiles = 1000;
          s.maxBuildMemoryMib = 512;
          s.maxBuildCpus = 2;
          s.maxBuildPids = 128;
          s.pollEnabled = true;
          s.pollGranularity = Settings.Granularity.PER_COMMIT;
          s.pollScope = Settings.Scope.WORKFLOW;
          s.pollIntervalSeconds = 60;
          s.mirrorManualBuilds = false;
          s.pullRequestsEnabled = false;
          s.articleEditors = Settings.ArticleEditors.ADMIN;
          s.persist();
        });
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void escapesLikeWildcards() {
    event(
        "probe",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        null,
        null,
        AuditTargetType.SETTINGS,
        null,
        null,
        "100% done",
        Instant.now(),
        Map.of());
    event(
        "probe",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        null,
        null,
        AuditTargetType.SETTINGS,
        null,
        null,
        "100x done",
        Instant.now(),
        Map.of());
    event(
        "probe",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        null,
        null,
        AuditTargetType.SETTINGS,
        null,
        null,
        "under_score",
        Instant.now(),
        Map.of());
    event(
        "probe",
        AuditOutcome.SUCCESS,
        "admin-id",
        "admin",
        null,
        null,
        AuditTargetType.SETTINGS,
        null,
        null,
        "underXscore",
        Instant.now(),
        Map.of());

    given().queryParam("search", "100%").get(PATH).then().statusCode(200).body("total", equalTo(1));
    given()
        .queryParam("search", "under_score")
        .get(PATH)
        .then()
        .statusCode(200)
        .body("total", equalTo(1));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void networkTemplatesHaveTheirOwnTargetType() {
    given().post("/api/network-hosts/templates/npm").then().statusCode(200);

    AuditEvent added = find("network.template.add", AuditOutcome.SUCCESS);
    assertEquals("NETWORK_TEMPLATE", added.targetType.name());
    assertEquals("npm", added.targetId);
    assertEquals("npm", added.targetName);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void projectCreationRollsBackWhenTheAuditInsertFails() {
    installFailingAuditTrigger();
    String name = "atomic-" + UUID.randomUUID();
    given()
        .contentType("application/json")
        .body(
            "{\"name\":\""
                + name
                + "\",\"repositoryUrl\":\"https://github.com/octocat/"
                + name
                + ".git\"}")
        .post("/api/projects")
        .then()
        .statusCode(500);
    assertEquals(0, QuarkusTransaction.requiringNew().call(() -> Project.count("name = ?1", name)));
    assertEquals(0, count("project.create"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(
      claims = {
        @Claim(key = "sub", value = "admin-id"),
        @Claim(key = "preferred_username", value = "admin")
      })
  void recordsUserLifecycle() {
    String username = "user" + UUID.randomUUID().toString().substring(0, 8);
    String id =
        given()
            .contentType("application/json")
            .body(
                "{\"username\":\""
                    + username
                    + "\",\"password\":\"secret-pass\",\"role\":\"member\",\"projectIds\":[]}")
            .post("/api/users")
            .then()
            .statusCode(201)
            .extract()
            .jsonPath()
            .getString("id");
    given()
        .contentType("application/json")
        .body("{\"enabled\":false}")
        .put("/api/users/" + id)
        .then()
        .statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"password\":\"another-secret\"}")
        .put("/api/users/" + id + "/password")
        .then()
        .statusCode(204);
    given().delete("/api/users/" + id).then().statusCode(204);

    AuditEvent created = find("user.create", AuditOutcome.SUCCESS);
    assertEquals(username, created.targetName);
    assertFalse(created.details.containsKey("password"));
    AuditEvent updated = find("user.update", AuditOutcome.SUCCESS);
    assertTrue(((java.util.List<?>) updated.details.get("changed")).contains("enabled"));
    AuditEvent password = find("user.password.reset", AuditOutcome.SUCCESS);
    assertFalse(password.details.containsValue("another-secret"));
    assertEquals(1, count("user.delete"));
  }
}
