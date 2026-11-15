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
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Delivery paths and edge cases that the main webhook test does not exercise. */
@QuarkusTest
class WebhookEdgeCaseTest {
  private static final String SECRET = "test-webhook-secret-0123456789";
  private static final String NEW_SECRET = "rotated-webhook-secret-9876543210";
  private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
  private static final String SHA2 = "1111111111111111111111111111111111111111";
  private static final String SHA3 = "2222222222222222222222222222222222222222";

  private Long projectId;
  private String repositoryUrl;

  @BeforeEach
  void createProject() {
    String name = "webhook-edge-" + UUID.randomUUID().toString().replace("-", "");
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

  private String path() {
    return "/api/webhooks/git/" + projectId;
  }

  private String repositoryName() {
    return repositoryUrl.substring(
        "https://github.com/example/".length(), repositoryUrl.length() - ".git".length());
  }

  private String payload(String ref, String fullName, String cloneUrl, String sha, boolean forced) {
    String clone = cloneUrl == null ? "" : ",\"clone_url\":\"" + cloneUrl + "\"";
    String repository = "{\"full_name\":\"" + fullName + "\"" + clone + "}";
    String body =
        """
        {
          "ref": "%s",
          "before": "0000000000000000000000000000000000000000",
          "after": "%s",
          "deleted": false,
          "forced": %s,
          "repository": %s,
          "pusher": {"name": "alice"}
        }
        """
            .formatted(ref, sha, forced, repository);
    return body;
  }

  private String payload(String ref, String sha) {
    return payload(ref, sha, false);
  }

  private String payload(String ref, String sha, boolean forced) {
    return payload(ref, "example/" + repositoryName(), repositoryUrl, sha, forced);
  }

  private static String sign(String secret, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  private Response post(Long id, String event, String delivery, String signature, String body) {
    var request = given().contentType("application/json");
    if (event != null) request.header("X-GitHub-Event", event);
    if (delivery != null) request.header("X-GitHub-Delivery", delivery);
    if (signature != null) request.header("X-Hub-Signature-256", signature);
    return request.body(body).post("/api/webhooks/git/" + id);
  }

  private Response post(String event, String delivery, String signature, String body) {
    return post(projectId, event, delivery, signature, body);
  }

  private long buildCount() {
    return QuarkusTransaction.requiringNew().call(() -> Build.count("projectId = ?1", projectId));
  }

  @Test
  void invalidJsonWithAValidSignatureIsRejected() throws Exception {
    String body = "{ this is not json";
    post("push", "edge-invalid-json", sign(SECRET, body), body).then().statusCode(400);
    assertEquals(0L, buildCount());
  }

  @Test
  void missingEventIsRejected() throws Exception {
    String body = payload("refs/heads/main", SHA);
    post(null, "edge-no-event", sign(SECRET, body), body)
        .then()
        .statusCode(400)
        .body("error", containsString("X-GitHub-Event"));
    assertEquals(0L, buildCount());
  }

  @Test
  void missingDeliveryIsRejected() throws Exception {
    String body = payload("refs/heads/main", SHA);
    post("push", null, sign(SECRET, body), body)
        .then()
        .statusCode(400)
        .body("error", containsString("X-GitHub-Delivery"));
    assertEquals(0L, buildCount());
  }

  @Test
  void deliveryIdLongerThanTheColumnIsRejected() throws Exception {
    String body = payload("refs/heads/main", SHA);
    post("push", "d".repeat(101), sign(SECRET, body), body)
        .then()
        .statusCode(400)
        .body("error", containsString("X-GitHub-Delivery"));
    assertEquals(0L, buildCount());
  }

  @Test
  void deliveryIdsWithUnexpectedCharactersAreRejected() throws Exception {
    String body = payload("refs/heads/main", SHA);
    for (String delivery : new String[] {"a.b", "a/b", "a b"})
      post("push", delivery, sign(SECRET, body), body)
          .then()
          .statusCode(400)
          .body("error", containsString("X-GitHub-Delivery"));
    assertEquals(0L, buildCount());
  }

  @Test
  void unknownProjectFailsAuthentication() throws Exception {
    String body = "{}";
    post(987_654_321L, "push", "edge-unknown", sign(SECRET, body), body).then().statusCode(401);
    assertEquals(0L, buildCount());
  }

  @Test
  void enabledProjectWithoutASecretFailsAuthentication() throws Exception {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project p = Project.findById(projectId);
          p.webhookSecret = null;
        });
    String body = payload("refs/heads/main", SHA);
    post("push", "edge-no-secret", sign(SECRET, body), body).then().statusCode(401);
    assertEquals(0L, buildCount());
  }

  @Test
  void branchFilterSupportsQuestionMarksAndExactNames() throws Exception {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project p = Project.findById(projectId);
          p.webhookBranches = "release/?,main";
        });
    String release = payload("refs/heads/release/1", SHA2);
    post("push", "edge-glob-1", sign(SECRET, release), release)
        .then()
        .statusCode(202)
        .body("status", equalTo("queued"));
    String tooDeep = payload("refs/heads/release/10", SHA3);
    post("push", "edge-glob-2", sign(SECRET, tooDeep), tooDeep)
        .then()
        .statusCode(202)
        .body("status", equalTo("ignored"), "reason", equalTo("branch"));
    String main = payload("refs/heads/main", SHA);
    post("push", "edge-glob-3", sign(SECRET, main), main)
        .then()
        .statusCode(202)
        .body("status", equalTo("queued"));
    assertEquals(2L, buildCount());
  }

  @Test
  void uppercaseCommitIsIgnored() throws Exception {
    String body = payload("refs/heads/main", SHA.toUpperCase(Locale.ROOT));
    post("push", "edge-uppercase", sign(SECRET, body), body)
        .then()
        .statusCode(202)
        .body("status", equalTo("ignored"), "reason", equalTo("commit"));
    assertEquals(0L, buildCount());
  }

  @Test
  void aMatchingFullNameWithoutACloneUrlQueuesABuild() throws Exception {
    String body = payload("refs/heads/main", "example/" + repositoryName(), null, SHA2, false);
    post("push", "edge-full-name", sign(SECRET, body), body)
        .then()
        .statusCode(202)
        .body("status", equalTo("queued"), "revision", equalTo(SHA2));
    assertEquals(1L, buildCount());
  }

  @Test
  void aFullNameFromAnotherOwnerIsRejected() throws Exception {
    String body = payload("refs/heads/main", "someone/" + repositoryName(), null, SHA, false);
    post("push", "edge-other-owner", sign(SECRET, body), body).then().statusCode(403);
    assertEquals(0L, buildCount());
  }

  @Test
  void aForcePushStillQueuesTheAfterCommit() throws Exception {
    String body = payload("refs/heads/main", SHA, true);
    post("push", "edge-forced", sign(SECRET, body), body)
        .then()
        .statusCode(202)
        .body("status", equalTo("queued"), "revision", equalTo(SHA));
    assertEquals(1L, buildCount());
  }

  @Test
  void anEmptyBranchNameIsIgnored() throws Exception {
    String body = payload("refs/heads/", SHA);
    post("push", "edge-empty-branch", sign(SECRET, body), body)
        .then()
        .statusCode(202)
        .body("status", equalTo("ignored"), "reason", equalTo("ref"));
    assertEquals(0L, buildCount());
  }

  @Test
  void aBranchNameStartingWithADashIsIgnored() throws Exception {
    String body = payload("refs/heads/-evil", SHA);
    post("push", "edge-dash-branch", sign(SECRET, body), body)
        .then()
        .statusCode(202)
        .body("status", equalTo("ignored"), "reason", equalTo("ref"));
    assertEquals(0L, buildCount());
  }

  @Test
  void aFailedBuildDoesNotDeduplicateANewDeliveryOfTheSameCommit() throws Exception {
    Long failedId =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Build b = new Build();
              b.projectId = projectId;
              b.number = Build.nextNumber(projectId);
              b.status = Build.Status.FAILED;
              b.trigger = Build.Trigger.PUSH;
              b.branch = "main";
              b.revision = SHA;
              b.triggerDelivery = "edge-old-delivery";
              b.requestedAt = java.time.Instant.now();
              b.persist();
              return b.id;
            });
    String body = payload("refs/heads/main", SHA);
    post("push", "edge-new-delivery", sign(SECRET, body), body)
        .then()
        .statusCode(202)
        .body("status", equalTo("queued"), "revision", equalTo(SHA));
    assertEquals(2L, buildCount());
    Build queued =
        QuarkusTransaction.requiringNew()
            .call(() -> Build.<Build>find("triggerDelivery", "edge-new-delivery").firstResult());
    assertNotNull(queued);
    assertNotEquals(failedId, queued.id);
  }

  @Test
  void rotatingTheSecretRejectsTheOldSignature() throws Exception {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Project p = Project.findById(projectId);
          p.webhookSecret = NEW_SECRET;
        });
    String body = payload("refs/heads/main", SHA);
    post("push", "edge-old-secret", sign(SECRET, body), body).then().statusCode(401);
    post("push", "edge-new-secret", sign(NEW_SECRET, body), body)
        .then()
        .statusCode(202)
        .body("status", equalTo("queued"));
    assertEquals(1L, buildCount());
  }

  @Test
  void getOnTheWebhookEndpointIsNotAllowed() {
    given().get(path()).then().statusCode(405);
  }

  @Test
  void concurrentDeliveriesOfTheSameCommitQueueOneBuild() throws Exception {
    String body = payload("refs/heads/main", SHA);
    String signature = sign(SECRET, body);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Integer>> responses = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String delivery = "edge-race-" + i;
        responses.add(
            pool.submit(() -> {
              start.await();
              return post("push", delivery, signature, body).statusCode();
            }));
      }
      start.countDown();
      for (Future<Integer> response : responses)
        assertEquals(202, response.get(30, TimeUnit.SECONDS));
      assertEquals(1L, buildCount());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void theDatabaseKeepsOneActivePushBuildForACommit() {
    persistBuild(Build.Trigger.PUSH, Build.Status.QUEUED, SHA3);
    assertThrows(
        RuntimeException.class, () -> persistBuild(Build.Trigger.PUSH, Build.Status.RUNNING, SHA3));
  }

  @Test
  void concurrentManualBuildsMayShareARevision() {
    // Manual builds set their revision while they are running, so the push-only index must not
    // reject a second manual build for the same commit.
    persistBuild(Build.Trigger.MANUAL, Build.Status.QUEUED, SHA3);
    persistBuild(Build.Trigger.MANUAL, Build.Status.RUNNING, SHA3);
    persistBuild(Build.Trigger.MANUAL, Build.Status.QUEUED, SHA3);
    assertEquals(
        3L, QuarkusTransaction.requiringNew().call(() -> Build.count("projectId = ?1", projectId)));
  }

  @Test
  void aPushAndAManualBuildMayShareAnActiveRevision() {
    persistBuild(Build.Trigger.PUSH, Build.Status.RUNNING, SHA3);
    persistBuild(Build.Trigger.MANUAL, Build.Status.RUNNING, SHA3);
    assertEquals(
        2L, QuarkusTransaction.requiringNew().call(() -> Build.count("projectId = ?1", projectId)));
  }

  @Test
  void aFinishedPushBuildDoesNotBlockTheSameCommit() {
    persistBuild(Build.Trigger.PUSH, Build.Status.FAILED, SHA3);
    persistBuild(Build.Trigger.PUSH, Build.Status.QUEUED, SHA3);
    assertEquals(
        2L, QuarkusTransaction.requiringNew().call(() -> Build.count("projectId = ?1", projectId)));
  }

  private void persistBuild(Build.Trigger trigger, Build.Status status, String revision) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build b = new Build();
          b.projectId = projectId;
          b.number = Build.nextNumber(projectId);
          b.status = status;
          b.trigger = trigger;
          b.branch = "main";
          b.revision = revision;
          b.requestedAt = java.time.Instant.now();
          b.persist();
        });
  }
}
