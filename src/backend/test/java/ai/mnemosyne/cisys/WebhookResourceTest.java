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
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WebhookResourceTest {
  private static final String SECRET = "test-webhook-secret-0123456789";
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

  @Inject BuildRunner runner;

  private Long projectId;
  private String repositoryUrl;

  @BeforeEach
  void createProject() {
    String name = "webhook-" + UUID.randomUUID().toString().replace("-", "");
    repositoryUrl = "https://github.com/example/" + name + ".git";
    projectId =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Project p = new Project();
              p.name = name;
              p.repositoryUrl = repositoryUrl;
              p.ownerSubject = "owner";
              p.webhookEnabled = true;
              p.webhookSecret = SECRET;
              p.webhookBranches = "*";
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

  private String payload(String ref, String repository, String sha) {
    return "{"
        + "\"ref\":\""
        + ref
        + "\","
        + "\"before\":\"0000000000000000000000000000000000000000\","
        + "\"after\":\""
        + sha
        + "\","
        + "\"deleted\":false,"
        + "\"repository\":{\"full_name\":\"example/"
        + repository
        + "\",\"clone_url\":\"https://github.com/example/"
        + repository
        + ".git\"},"
        + "\"pusher\":{\"name\":\"alice\"}"
        + "}";
  }

  private static String sign(String secret, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  private io.restassured.response.Response post(
      String event, String delivery, String signature, String body) {
    var request =
        given()
            .contentType("application/json")
            .header("X-GitHub-Event", event)
            .header("X-GitHub-Delivery", delivery)
            .body(body);
    if (signature != null) request.header("X-Hub-Signature-256", signature);
    return request.post("/api/webhooks/git/" + projectId);
  }

  private String repositoryName() {
    return repositoryUrl.substring(
        "https://github.com/example/".length(), repositoryUrl.length() - ".git".length());
  }

  @Test
  void queuedPushBuildUsesTheStoredRepositoryAndPushedCommit() throws Exception {
    String body = payload("refs/heads/main", repositoryName(), SHA);
    post("push", "delivery-1", sign(SECRET, body), body)
        .then()
        .statusCode(202)
        .body("status", equalTo("queued"), "revision", equalTo(SHA));

    Build build =
        QuarkusTransaction.requiringNew()
            .call(() -> Build.<Build>find("projectId = ?1", projectId).firstResult());
    assertNotNull(build);
    assertEquals(repositoryUrl, build.repositoryUrl);
    assertEquals("main", build.branch);
    assertEquals(SHA, build.revision);
    assertEquals(Build.Trigger.PUSH, build.trigger);
    assertEquals("delivery-1", build.triggerDelivery);
    assertEquals("alice", build.triggerActor);
  }

  @Test
  void rejectsAMissingOrInvalidSignature() {
    String body = payload("refs/heads/main", repositoryName(), SHA);
    post("push", "delivery-2", null, body).then().statusCode(401);
    post("push", "delivery-3", "sha256=" + "0".repeat(64), body).then().statusCode(401);
    assertEquals(
        0L, QuarkusTransaction.requiringNew().call(() -> Build.count("projectId = ?1", projectId)));
  }

  @Test
  void disabledProjectIsNotFound() throws Exception {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project p = Project.findById(projectId);
          p.webhookEnabled = false;
        });
    String body = payload("refs/heads/main", repositoryName(), SHA);
    post("push", "delivery-4", sign(SECRET, body), body).then().statusCode(404);
  }

  @Test
  void ignoresPingAndNonPushEvents() throws Exception {
    String body = "{}";
    post("ping", "delivery-5", sign(SECRET, body), body)
        .then()
        .statusCode(202)
        .body("status", equalTo("ignored"), "reason", equalTo("ping"));
    post("issues", "delivery-6", sign(SECRET, body), body)
        .then()
        .statusCode(202)
        .body("status", equalTo("ignored"), "reason", equalTo("event"));
  }

  @Test
  void ignoresTagsAndDeletedBranches() throws Exception {
    String tag = payload("refs/tags/v1", repositoryName(), SHA);
    post("push", "delivery-7", sign(SECRET, tag), tag)
        .then()
        .statusCode(202)
        .body("reason", equalTo("ref"));

    String deleted =
        payload("refs/heads/main", repositoryName(), "0000000000000000000000000000000000000000");
    post("push", "delivery-8", sign(SECRET, deleted), deleted)
        .then()
        .statusCode(202)
        .body("reason", equalTo("commit"));
  }

  @Test
  void rejectsAnotherRepository() throws Exception {
    String body = payload("refs/heads/main", "other/repository", SHA);
    post("push", "delivery-9", sign(SECRET, body), body).then().statusCode(403);
  }

  @Test
  void branchFilterSkipsOtherBranches() throws Exception {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project p = Project.findById(projectId);
          p.webhookBranches = "main,release/*";
        });
    String feature = payload("refs/heads/feature", repositoryName(), SHA);
    post("push", "delivery-10", sign(SECRET, feature), feature)
        .then()
        .statusCode(202)
        .body("reason", equalTo("branch"));
    String release = payload("refs/heads/release/1.0", repositoryName(), SHA);
    post("push", "delivery-11", sign(SECRET, release), release)
        .then()
        .statusCode(202)
        .body("status", equalTo("queued"));
  }

  @Test
  void duplicateDeliveryAndCommitCreateOneBuild() throws Exception {
    String body = payload("refs/heads/main", repositoryName(), SHA);
    String signature = sign(SECRET, body);
    int first =
        post("push", "delivery-12", signature, body)
            .then()
            .statusCode(202)
            .body("status", equalTo("queued"))
            .extract()
            .path("buildId");
    post("push", "delivery-12", signature, body)
        .then()
        .statusCode(202)
        .body("status", equalTo("duplicate"), "buildId", equalTo(first));
    post("push", "delivery-13", signature, body)
        .then()
        .statusCode(202)
        .body("status", equalTo("duplicate_commit"), "buildId", equalTo(first));
    assertEquals(
        1L, QuarkusTransaction.requiringNew().call(() -> Build.count("projectId = ?1", projectId)));
  }

  @Test
  void aFullQueueReturnsServiceUnavailable() throws Exception {
    String body = payload("refs/heads/main", repositoryName(), SHA);
    BuildRunner target = io.quarkus.arc.ClientProxy.unwrap(runner);
    int limit = target.queueLimit;
    SettingsResourceTest.clearQueue();
    target.queueLimit = 0;
    try {
      post("push", "delivery-14", sign(SECRET, body), body)
          .then()
          .statusCode(503)
          .body("error", containsString("queue is full"));
      assertEquals(
          0L,
          QuarkusTransaction.requiringNew().call(() -> Build.count("projectId = ?1", projectId)));
    } finally {
      target.queueLimit = limit;
      SettingsResourceTest.clearQueue();
    }
  }

  @Test
  void rejectsAnOversizedBodyBeforeParsing() throws Exception {
    String body =
        payload("refs/heads/main", repositoryName(), SHA) + " ".repeat(WebhookSignature.MAX_BYTES);
    post("push", "delivery-15", sign(SECRET, body), body).then().statusCode(413);
  }
}
