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
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class SettingsResourceTest {
  @Inject BuildRunner runner;
  @Inject CommitPoller poller;

  private static final String PATH = "/api/settings/retention";
  static final String DEFAULTS = "{\"maxAgeDays\":30,\"maxBuilds\":50}";

  @Test
  void anonymousCannotRead() {
    given().get(PATH).then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  void leadCannotReadOrUpdate() {
    given().get(PATH).then().statusCode(403);
    given().contentType("application/json").body(DEFAULTS).put(PATH).then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  void memberCannotUpdate() {
    given().contentType("application/json").body(DEFAULTS).put(PATH).then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void adminSavesRetention() {
    try {
      given()
          .contentType("application/json")
          .body("{\"maxAgeDays\":7,\"maxBuilds\":0}")
          .put(PATH)
          .then()
          .statusCode(200)
          .body("maxAgeDays", equalTo(7), "maxBuilds", equalTo(0));
      given()
          .get(PATH)
          .then()
          .statusCode(200)
          .body("maxAgeDays", equalTo(7), "maxBuilds", equalTo(0));
    } finally {
      given().contentType("application/json").body(DEFAULTS).put(PATH).then().statusCode(200);
    }
    given()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("maxAgeDays", equalTo(30), "maxBuilds", equalTo(50));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void rejectsInvalidLimits() {
    for (String body :
        new String[] {
          "{\"maxAgeDays\":-1,\"maxBuilds\":50}",
          "{\"maxAgeDays\":30,\"maxBuilds\":-1}",
          "{\"maxAgeDays\":3651,\"maxBuilds\":50}",
          "{\"maxAgeDays\":30,\"maxBuilds\":10001}",
          "{\"maxAgeDays\":30}",
          "{}"
        }) {
      given().contentType("application/json").body(body).put(PATH).then().statusCode(400);
    }
  }

  private static final String QUEUE = "/api/settings/builds";

  static void clearQueue() {
    QuarkusTransaction.requiringNew()
        .run(() ->
            Settings.update(
                "maxConcurrentBuilds = null, maxQueuedBuilds = null, buildTimeoutMinutes = null"
                    + " where id = 1"));
  }

  @Test
  void anonymousCannotReadQueue() {
    given().get(QUEUE).then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  void leadCannotReadOrUpdateQueue() {
    given().get(QUEUE).then().statusCode(403);
    given()
        .contentType("application/json")
        .body("{\"maxConcurrent\":2,\"maxQueued\":10,\"timeoutMinutes\":30}")
        .put(QUEUE)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void adminSavesQueueOverConfiguredDefaults() {
    given()
        .get(QUEUE)
        .then()
        .statusCode(200)
        .body(
            "maxConcurrent", equalTo(1), "maxQueued", equalTo(100), "timeoutMinutes", equalTo(10));
    try {
      given()
          .contentType("application/json")
          .body("{\"maxConcurrent\":4,\"maxQueued\":10,\"timeoutMinutes\":45}")
          .put(QUEUE)
          .then()
          .statusCode(200)
          .body(
              "maxConcurrent", equalTo(4), "maxQueued", equalTo(10), "timeoutMinutes", equalTo(45));
      given()
          .get(QUEUE)
          .then()
          .statusCode(200)
          .body(
              "maxConcurrent", equalTo(4), "maxQueued", equalTo(10), "timeoutMinutes", equalTo(45));
      // The runner uses the saved timeout for the next build.
      assertEquals(45, (int) QuarkusTransaction.requiringNew().call(() -> runner.timeoutMinutes()));
    } finally {
      clearQueue();
    }
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void rejectsInvalidQueue() {
    for (String body :
        new String[] {
          "{\"maxConcurrent\":0,\"maxQueued\":10,\"timeoutMinutes\":10}",
          "{\"maxConcurrent\":17,\"maxQueued\":10,\"timeoutMinutes\":10}",
          "{\"maxConcurrent\":1,\"maxQueued\":0,\"timeoutMinutes\":10}",
          "{\"maxConcurrent\":1,\"maxQueued\":1001,\"timeoutMinutes\":10}",
          "{\"maxConcurrent\":1,\"maxQueued\":10,\"timeoutMinutes\":0}",
          "{\"maxConcurrent\":1,\"maxQueued\":10,\"timeoutMinutes\":1441}",
          "{\"maxConcurrent\":1,\"maxQueued\":10}",
          "{\"maxConcurrent\":1}",
          "{}"
        }) {
      given().contentType("application/json").body(body).put(QUEUE).then().statusCode(400);
    }
  }

  private static final String CHECKOUT = "/api/settings/checkout";

  static void clearCheckout() {
    QuarkusTransaction.requiringNew()
        .run(() -> Settings.update("maxCheckoutMib = null, maxCheckoutFiles = null where id = 1"));
  }

  @Test
  void anonymousCannotReadCheckout() {
    given().get(CHECKOUT).then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  void leadCannotReadOrUpdateCheckout() {
    given().get(CHECKOUT).then().statusCode(403);
    given()
        .contentType("application/json")
        .body("{\"maxSizeMib\":10,\"maxFiles\":10}")
        .put(CHECKOUT)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void adminSavesCheckoutOverConfiguredDefaults() {
    given()
        .get(CHECKOUT)
        .then()
        .statusCode(200)
        .body("maxSizeMib", equalTo(1024), "maxFiles", equalTo(100000));
    try {
      given()
          .contentType("application/json")
          .body("{\"maxSizeMib\":64,\"maxFiles\":500}")
          .put(CHECKOUT)
          .then()
          .statusCode(200)
          .body("maxSizeMib", equalTo(64), "maxFiles", equalTo(500));
      given()
          .get(CHECKOUT)
          .then()
          .statusCode(200)
          .body("maxSizeMib", equalTo(64), "maxFiles", equalTo(500));
    } finally {
      clearCheckout();
    }
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void rejectsInvalidCheckout() {
    for (String body :
        new String[] {
          "{\"maxSizeMib\":0,\"maxFiles\":10}",
          "{\"maxSizeMib\":102401,\"maxFiles\":10}",
          "{\"maxSizeMib\":1,\"maxFiles\":0}",
          "{\"maxSizeMib\":1,\"maxFiles\":1000001}",
          "{\"maxSizeMib\":1}",
          "{}"
        }) {
      given().contentType("application/json").body(body).put(CHECKOUT).then().statusCode(400);
    }
  }

  private static final String PERFORMANCE = "/api/settings/performance";

  static void clearPerformance() {
    QuarkusTransaction.requiringNew()
        .run(() ->
            Settings.update(
                "maxBuildMemoryMib = null, maxBuildCpus = null, maxBuildPids = null"
                    + " where id = 1"));
  }

  @Test
  void anonymousCannotReadPerformance() {
    given().get(PERFORMANCE).then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  void leadCannotReadOrUpdatePerformance() {
    given().get(PERFORMANCE).then().statusCode(403);
    given()
        .contentType("application/json")
        .body("{\"maxMemoryMib\":512,\"maxCpus\":2,\"maxPids\":256}")
        .put(PERFORMANCE)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void adminSavesPerformanceOverConfiguredDefaults() {
    given()
        .get(PERFORMANCE)
        .then()
        .statusCode(200)
        .body("maxMemoryMib", equalTo(1024), "maxCpus", equalTo(1), "maxPids", equalTo(512));
    try {
      given()
          .contentType("application/json")
          .body("{\"maxMemoryMib\":2048,\"maxCpus\":2,\"maxPids\":256}")
          .put(PERFORMANCE)
          .then()
          .statusCode(200)
          .body("maxMemoryMib", equalTo(2048), "maxCpus", equalTo(2), "maxPids", equalTo(256));
      given()
          .get(PERFORMANCE)
          .then()
          .statusCode(200)
          .body("maxMemoryMib", equalTo(2048), "maxCpus", equalTo(2), "maxPids", equalTo(256));
    } finally {
      clearPerformance();
    }
    given()
        .get(PERFORMANCE)
        .then()
        .statusCode(200)
        .body("maxMemoryMib", equalTo(1024), "maxCpus", equalTo(1), "maxPids", equalTo(512));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void rejectsInvalidPerformance() {
    for (String body :
        new String[] {
          "{\"maxMemoryMib\":63,\"maxCpus\":1,\"maxPids\":512}",
          "{\"maxMemoryMib\":65537,\"maxCpus\":1,\"maxPids\":512}",
          "{\"maxMemoryMib\":1024,\"maxCpus\":0,\"maxPids\":512}",
          "{\"maxMemoryMib\":1024,\"maxCpus\":17,\"maxPids\":512}",
          "{\"maxMemoryMib\":1024,\"maxCpus\":1,\"maxPids\":15}",
          "{\"maxMemoryMib\":1024,\"maxCpus\":1,\"maxPids\":32769}",
          "{\"maxMemoryMib\":1024,\"maxCpus\":1}",
          "{}"
        }) {
      given().contentType("application/json").body(body).put(PERFORMANCE).then().statusCode(400);
    }
  }

  private static final String POLLING = "/api/settings/polling";

  static void clearPolling() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Settings s = Settings.findById(1L);
          if (s == null) return;
          s.pollEnabled = true;
          s.pollGranularity = Settings.Granularity.PER_COMMIT;
          s.pollScope = Settings.Scope.WORKFLOW;
          s.pollIntervalSeconds = null;
        });
  }

  @Test
  void anonymousCannotReadPolling() {
    given().get(POLLING).then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  void leadCannotReadOrUpdatePolling() {
    given().get(POLLING).then().statusCode(403);
    given()
        .contentType("application/json")
        .body(
            "{\"enabled\":false,\"granularity\":\"TIP\",\"scope\":\"MAIN_ONLY\",\"intervalSeconds\":60}")
        .put(POLLING)
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void adminSavesPolling() {
    given()
        .get(POLLING)
        .then()
        .statusCode(200)
        .body(
            "enabled",
            equalTo(true),
            "granularity",
            equalTo("PER_COMMIT"),
            "scope",
            equalTo("WORKFLOW"),
            "intervalSeconds",
            equalTo(60));
    try {
      given()
          .contentType("application/json")
          .body(
              "{\"enabled\":false,\"granularity\":\"TIP\",\"scope\":\"MAIN_ONLY\",\"intervalSeconds\":120}")
          .put(POLLING)
          .then()
          .statusCode(200)
          .body(
              "enabled",
              equalTo(false),
              "granularity",
              equalTo("TIP"),
              "scope",
              equalTo("MAIN_ONLY"),
              "intervalSeconds",
              equalTo(120));
      given()
          .get(POLLING)
          .then()
          .statusCode(200)
          .body("enabled", equalTo(false), "granularity", equalTo("TIP"));
    } finally {
      clearPolling();
    }
    given()
        .get(POLLING)
        .then()
        .statusCode(200)
        .body("enabled", equalTo(true), "granularity", equalTo("PER_COMMIT"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void clampsAConfiguredDefaultIntervalToTheAcceptedRange() {
    CommitPoller target = io.quarkus.arc.ClientProxy.unwrap(poller);
    java.time.Duration original = target.pollEvery;
    try {
      clearPolling();
      target.pollEvery = java.time.Duration.ofSeconds(1);
      given().get(POLLING).then().statusCode(200).body("intervalSeconds", equalTo(5));
      target.pollEvery = java.time.Duration.ofSeconds(90000);
      given().get(POLLING).then().statusCode(200).body("intervalSeconds", equalTo(86400));
    } finally {
      target.pollEvery = original;
      clearPolling();
    }
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void acceptsAndClearsThePollingIntervalBoundaries() {
    try {
      for (int seconds : new int[] {5, 86400}) {
        given()
            .contentType("application/json")
            .body(
                "{\"enabled\":true,\"granularity\":\"PER_COMMIT\",\"scope\":\"WORKFLOW\",\"intervalSeconds\":"
                    + seconds
                    + "}")
            .put(POLLING)
            .then()
            .statusCode(200)
            .body("intervalSeconds", equalTo(seconds));
      }
      // Null means "use cisys.poll.every", so the endpoint echoes the effective default again.
      given()
          .contentType("application/json")
          .body(
              "{\"enabled\":true,\"granularity\":\"PER_COMMIT\",\"scope\":\"WORKFLOW\",\"intervalSeconds\":null}")
          .put(POLLING)
          .then()
          .statusCode(200)
          .body("intervalSeconds", equalTo(60));
      assertEquals(
          null,
          QuarkusTransaction.requiringNew().call(() -> Settings.current().pollIntervalSeconds),
          "a null interval must be stored as null, not pinned to the default");
    } finally {
      clearPolling();
    }
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void rejectsInvalidPolling() {
    for (String body :
        new String[] {
          "{\"granularity\":\"TIP\",\"scope\":\"WORKFLOW\",\"intervalSeconds\":60}",
          "{\"enabled\":true,\"scope\":\"WORKFLOW\",\"intervalSeconds\":60}",
          "{\"enabled\":true,\"granularity\":\"TIP\",\"intervalSeconds\":60}",
          "{\"enabled\":true,\"granularity\":\"TIP\",\"scope\":\"WORKFLOW\",\"intervalSeconds\":4}",
          "{\"enabled\":true,\"granularity\":\"TIP\",\"scope\":\"WORKFLOW\",\"intervalSeconds\":86401}",
          "{}"
        }) {
      given().contentType("application/json").body(body).put(POLLING).then().statusCode(400);
    }
  }
}
