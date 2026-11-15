/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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

/** Failed webhook authentication is throttled per project without blocking valid deliveries. */
@QuarkusTest
class WebhookThrottleTest {
  private static final String SECRET = "test-webhook-secret-0123456789";

  @Inject WebhookFailureLimiter failures;

  private int requests, windowSeconds, maxClients;
  private Long projectId;

  @BeforeEach
  void saveAndReset() {
    failures = io.quarkus.arc.ClientProxy.unwrap(failures);
    requests = failures.requests;
    windowSeconds = failures.windowSeconds;
    maxClients = failures.maxClients;
    failures.reset();
    String name = "webhook-throttle-" + UUID.randomUUID().toString().replace("-", "");
    projectId =
        QuarkusTransaction.requiringNew()
            .call(() -> {
              Project p = new Project();
              p.name = name;
              p.repositoryUrl = "https://github.com/example/" + name + ".git";
              p.ownerSubject = "owner";
              p.webhookEnabled = true;
              p.webhookSecret = SECRET;
              p.webhookBranches = "*";
              p.persist();
              return p.id;
            });
  }

  @AfterEach
  void restore() {
    // Restore the shared limiter first, so a failing cleanup cannot poison later tests.
    failures.requests = requests;
    failures.windowSeconds = windowSeconds;
    failures.maxClients = maxClients;
    failures.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build.delete("projectId", projectId);
          Project.deleteById(projectId);
        });
  }

  private static String sign(String secret, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  private io.restassured.response.Response post(String signature, String body) {
    var request =
        given()
            .contentType("application/json")
            .header("X-GitHub-Event", "push")
            .header("X-GitHub-Delivery", "throttle-" + UUID.randomUUID())
            .body(body);
    if (signature != null) request.header("X-Hub-Signature-256", signature);
    return request.post("/api/webhooks/git/" + projectId);
  }

  @Test
  void failedSignaturesAreThrottledWithoutBlockingAValidDelivery() throws Exception {
    failures.requests = 2;
    failures.windowSeconds = 60;
    String body = "{}";
    String bad = "sha256=" + "0".repeat(64);
    post(bad, body).then().statusCode(401);
    post(bad, body).then().statusCode(401);
    post(bad, body)
        .then()
        .statusCode(429)
        .header("Retry-After", notNullValue())
        .body("error", equalTo("Too many failed webhook attempts. Try again later."));
    // Failures are recorded after authentication, so a correctly signed delivery still runs.
    post(sign(SECRET, body), body).then().statusCode(202).body("status", equalTo("ignored"));
  }

  @Test
  void malformedSignaturesCountWithoutReadingTheBody() {
    failures.requests = 1;
    failures.windowSeconds = 60;
    post(null, "{}").then().statusCode(401);
    post(null, "{}").then().statusCode(429);
  }

  @Test
  void concurrentFailedRequestsCannotBypassTheLimit() throws Exception {
    failures.requests = 5;
    failures.windowSeconds = 60;
    int attempts = 40;
    ExecutorService pool = Executors.newFixedThreadPool(16);
    try {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Integer>> responses = new ArrayList<>();
      for (int i = 0; i < attempts; i++) {
        responses.add(
            pool.submit(() -> {
              start.await();
              return post(null, "{}").statusCode();
            }));
      }
      start.countDown();
      int rejected = 0;
      for (Future<Integer> response : responses) {
        int status = response.get(30, TimeUnit.SECONDS);
        if (status == 429) rejected++;
        else assertEquals(401, status);
      }
      // The reservation is atomic, so exactly the configured number of attempts get through.
      assertEquals(attempts - 5, rejected);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void authenticatedDeliveriesDoNotSpendTheFailureBudget() throws Exception {
    failures.requests = 1;
    failures.windowSeconds = 60;
    String ping = "{}";
    for (int i = 0; i < 5; i++)
      post(sign(SECRET, ping), ping).then().statusCode(202).body("status", equalTo("ignored"));
    // One failure is allowed, and it does not block an authenticated delivery.
    post("sha256=" + "0".repeat(64), ping).then().statusCode(401);
    post(sign(SECRET, ping), ping).then().statusCode(202).body("status", equalTo("ignored"));
    // The next failure is over the budget.
    post("sha256=" + "0".repeat(64), ping).then().statusCode(429);
  }

  @Test
  void failureWindowsArePerProjectAndExpire() {
    failures.requests = 2;
    failures.windowSeconds = 60;
    failures.reset();
    long now = 1_000_000L;
    assertEquals(0, failures.recordFailure("project-1", now));
    assertEquals(0, failures.recordFailure("project-1", now));
    assertTrue(failures.recordFailure("project-1", now) > 0);
    // A new window starts fresh, and another project has its own budget.
    assertEquals(0, failures.recordFailure("project-1", now + 60_000));
    assertEquals(0, failures.recordFailure("project-2", now));
  }

  @Test
  void aZeroLimitDisablesTheThrottle() {
    failures.requests = 0;
    failures.reset();
    long now = 1_000_000L;
    for (int i = 0; i < 100; i++) assertEquals(0, failures.recordFailure("project-1", now));
  }
}
