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
import static org.hamcrest.Matchers.startsWith;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The health and metrics endpoints of the Quarkus extensions on the management interface.
 *
 * <p>They are not Jakarta REST resources and the management interface has no authentication by
 * default, so they answer without a token, while the API keeps its own authentication. The test
 * profile sets {@code quarkus.oidc.enabled=false}, so these tests prove that the routes answer
 * anonymously. They cannot prove how an {@code Authorization} header is handled: the management
 * interface ignores it, so a stale token is not rejected. That behavior is verified manually with
 * curl against {@code quarkus:dev}.
 */
@QuarkusTest
class HealthEndpointTest {
  private static final int MANAGEMENT_PORT = 9001;

  @Test
  void anonymousCanReadHealth() {
    given()
        .port(MANAGEMENT_PORT)
        .get("/health")
        .then()
        .statusCode(200)
        .body("status", equalTo("UP"));
  }

  @Test
  void anonymousCanReadLiveness() {
    given()
        .port(MANAGEMENT_PORT)
        .get("/health/live")
        .then()
        .statusCode(200)
        .body("status", equalTo("UP"));
  }

  @Test
  void anonymousCanReadReadiness() {
    given()
        .port(MANAGEMENT_PORT)
        .get("/health/ready")
        .then()
        .statusCode(200)
        .body("status", equalTo("UP"));
  }

  /** The datasource of Agroal is checked for readiness, so a broken database shows up here. */
  @Test
  void readinessReportsTheDatabaseCheck() {
    given()
        .port(MANAGEMENT_PORT)
        .get("/health/ready")
        .then()
        .statusCode(200)
        .body(
            "checks.find { it.name == 'Database connections health check' }.status", equalTo("UP"));
  }

  @Test
  void anonymousCanReadPrometheusMetrics() {
    given()
        .port(MANAGEMENT_PORT)
        .accept("text/plain")
        .get("/metrics")
        .then()
        .statusCode(200)
        .contentType(startsWith("text/plain"))
        .body(containsString("# HELP"))
        .body(containsString("http_server_requests"));
  }

  /**
   * The monitoring endpoints moved to the management port, so the main port does not serve them.
   */
  @Test
  void managementEndpointsAreNotServedByTheMainPort() {
    given().get("/q/health").then().statusCode(404);
    given().get("/health").then().statusCode(404);
  }

  /** The monitoring endpoints are on the management port only; the API is not public. */
  @Test
  void apiEndpointsStillRequireAToken() {
    given().get("/api/projects").then().statusCode(401);
  }
}
