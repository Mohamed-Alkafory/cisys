/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ProjectWebhookResourceTest {
  private Long projectId;

  @BeforeEach
  void createProject() {
    String name = "settings-" + UUID.randomUUID().toString().replace("-", "");
    projectId =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Project p = new Project();
              p.name = name;
              p.repositoryUrl = "https://github.com/example/" + name + ".git";
              p.ownerSubject = "owner-id";
              p.privateRepository = true;
              p.persist();
              return p.id;
            });
  }

  @AfterEach
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build.delete("projectId", projectId);
          Project.deleteById(projectId);
        });
  }

  private String path() {
    return "/api/projects/" + projectId + "/webhook";
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void ownerGeneratesASecretAndEnablesTheWebhook() {
    given()
        .get(path())
        .then()
        .statusCode(200)
        .body(
            "enabled",
            equalTo(false),
            "url",
            endsWith("/api/webhooks/git/" + projectId),
            "branches",
            equalTo("*"),
            "secretSet",
            equalTo(false),
            "$",
            not(hasKey("secret")));

    given()
        .post(path() + "/secret")
        .then()
        .statusCode(200)
        .header("Cache-Control", "no-store")
        .body("secret", matchesPattern("[A-Za-z0-9_-]{43}"));

    given().get(path()).then().statusCode(200).body("secretSet", equalTo(true));
    given()
        .get(path() + "/secret")
        .then()
        .statusCode(200)
        .header("Cache-Control", "no-store")
        .body("secret", matchesPattern("[A-Za-z0-9_-]{43}"));

    given()
        .contentType("application/json")
        .body("{\"enabled\":true,\"branches\":\"main,release/*\"}")
        .put(path())
        .then()
        .statusCode(200)
        .body("enabled", equalTo(true), "branches", equalTo("main,release/*"));

    Long buildId =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Build b = new Build();
              b.projectId = projectId;
              b.number = Build.nextNumber(projectId);
              b.status = Build.Status.PASSED;
              b.trigger = Build.Trigger.PUSH;
              b.branch = "main";
              b.revision = "0123456789abcdef0123456789abcdef01234567";
              b.requestedAt = java.time.Instant.now();
              b.persist();
              return b.id;
            });
    given()
        .get(path())
        .then()
        .statusCode(200)
        .body("lastBuild.id", equalTo(buildId.intValue()), "lastBuild.branch", equalTo("main"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void cannotEnableWithoutASecret() {
    given()
        .contentType("application/json")
        .body("{\"enabled\":true}")
        .put(path())
        .then()
        .statusCode(400)
        .body("error", containsString("secret"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
  void rejectsAnEmptyOrOverlongBranchFilter() {
    // The project needs a secret before the branch filter can be saved with enabled=true.
    given().post(path() + "/secret").then().statusCode(200);
    for (String branches :
        new String[] {"main,,release", "main,release/* extra", "x".repeat(151)}) {
      given()
          .contentType("application/json")
          .body("{\"enabled\":true,\"branches\":\"" + branches + "\"}")
          .put(path())
          .then()
          .statusCode(400);
    }
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void anotherLeadCannotSeeAPrivateProject() {
    given().get(path()).then().statusCode(404);
    given().post(path() + "/secret").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void memberCannotManageTheWebhook() {
    given().get(path()).then().statusCode(403);
    given().post(path() + "/secret").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCanManageAnyProjectAndDisableIt() {
    given().post(path() + "/secret").then().statusCode(200);
    given()
        .contentType("application/json")
        .body("{\"enabled\":true}")
        .put(path())
        .then()
        .statusCode(200)
        .body("enabled", equalTo(true));
    given().delete(path()).then().statusCode(204);
    given()
        .get(path())
        .then()
        .statusCode(200)
        .body("enabled", equalTo(false), "secretSet", equalTo(false));
    Project p = QuarkusTransaction.requiringNew().call(() -> Project.<Project>findById(projectId));
    assertFalse(p.webhookEnabled);
    assertNull(p.webhookSecret);
    assertNull(p.webhookBranches);
  }
}
