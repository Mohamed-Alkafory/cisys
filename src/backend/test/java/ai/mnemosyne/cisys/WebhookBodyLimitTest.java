/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The body limit comes from configuration and is checked after the signature shape. */
@QuarkusTest
@TestProfile(WebhookBodyLimitTest.SmallLimit.class)
class WebhookBodyLimitTest {
  public static class SmallLimit implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("cisys.webhook.max-bytes", "64");
    }
  }

  private static final String SECRET = "test-webhook-secret-0123456789";
  private static final String BODY =
      "{\"ref\":\"refs/heads/main\",\"after\":\"0123456789abcdef0123456789abcdef01234567\"}";

  private Long projectId;

  @BeforeEach
  void createProject() {
    String name = "webhook-limit-" + UUID.randomUUID().toString().replace("-", "");
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
  void cleanup() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Build.delete("projectId", projectId);
          Project.deleteById(projectId);
        });
  }

  private static String sign(String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
  }

  private io.restassured.response.Response post(String signature) {
    return given()
        .contentType("application/json")
        .header("X-GitHub-Event", "push")
        .header("X-GitHub-Delivery", "limit-" + UUID.randomUUID())
        .header("X-Hub-Signature-256", signature)
        .body(BODY)
        .post("/api/webhooks/git/" + projectId);
  }

  @Test
  void aMalformedSignatureIsRejectedBeforeTheBodyLimit() {
    // The body is over the configured 64 bytes, but a header that cannot be a signature wins.
    post("sha256=" + "0".repeat(63)).then().statusCode(401);
  }

  @Test
  void aCorrectlySignedBodyOverTheConfiguredLimitIsRejected() throws Exception {
    post(sign(BODY)).then().statusCode(413);
  }
}
