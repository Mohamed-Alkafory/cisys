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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class NetworkHostResourceTest {
  private static final String PATH = "/api/network-hosts";

  static void clear() {
    QuarkusTransaction.requiringNew().run(() -> NetworkHost.deleteAll());
  }

  @BeforeEach
  @AfterEach
  void reset() {
    clear();
  }

  private static long create(String name, String host) {
    return given()
        .contentType("application/json")
        .body("{\"name\":\"" + name + "\",\"host\":\"" + host + "\"}")
        .post(PATH)
        .then()
        .statusCode(201)
        .extract()
        .jsonPath()
        .getLong("id");
  }

  @Test
  void anonymousCannotRead() {
    given().get(PATH).then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  void leadCannotReadOrChange() {
    given().get(PATH).then().statusCode(403);
    given().get(PATH + "/templates").then().statusCode(403);
    given()
        .contentType("application/json")
        .body("{\"name\":\"npm\",\"host\":\"registry.npmjs.org\"}")
        .post(PATH)
        .then()
        .statusCode(403);
    given().post(PATH + "/templates/npm").then().statusCode(403);
    given().delete(PATH + "/1").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void adminAddsEditsAndRemovesHosts() {
    given().get(PATH).then().statusCode(200).body("size()", equalTo(0));
    long id = create("npm", " Registry.NPMjs.org ");
    given()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].name", equalTo("npm"))
        .body("[0].host", equalTo("registry.npmjs.org"))
        .body("[0].createdBy", equalTo("admin"))
        .body("[0].createdAt", notNullValue());
    given()
        .contentType("application/json")
        .body("{\"name\":\"Yarn\",\"host\":\"registry.yarnpkg.com\"}")
        .put(PATH + "/" + id)
        .then()
        .statusCode(200)
        .body("name", equalTo("Yarn"))
        .body("host", equalTo("registry.yarnpkg.com"));
    assertEquals(
        List.of("registry.yarnpkg.com"),
        QuarkusTransaction.requiringNew().call(NetworkHost::allowlist).entries());
    given().delete(PATH + "/" + id).then().statusCode(204);
    given().delete(PATH + "/" + id).then().statusCode(404);
    assertTrue(QuarkusTransaction.requiringNew().call(NetworkHost::allowlist).isEmpty());
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void allMeansAnyPublicHost() {
    create("Everything", "all");
    given().get(PATH).then().body("[0].host", equalTo("*"));
    assertTrue(QuarkusTransaction.requiringNew().call(NetworkHost::allowlist).allowsAny());
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void rejectsInvalidProtectedAndDuplicateHosts() {
    given()
        .contentType("application/json")
        .body("{\"name\":\"internal\",\"host\":\"10.0.0.0/8\"}")
        .post(PATH)
        .then()
        .statusCode(400)
        .body("error", equalTo("Network entry 10.0.0.0/8 covers protected addresses"));
    given()
        .contentType("application/json")
        .body("{\"name\":\"bad\",\"host\":\"not a host\"}")
        .post(PATH)
        .then()
        .statusCode(400)
        .body("error", equalTo("Invalid network entry: not a host"));
    for (String body :
        new String[] {
          "{\"name\":\"\",\"host\":\"a.example.com\"}",
          "{\"name\":\"" + "n".repeat(101) + "\",\"host\":\"a.example.com\"}",
          "{\"name\":\"x\",\"host\":\"\"}",
          "{\"name\":\"x\"}",
          "{}"
        }) {
      given().contentType("application/json").body(body).post(PATH).then().statusCode(400);
    }
    long id = create("a", "a.example.com");
    create("b", "b.example.com");
    given()
        .contentType("application/json")
        .body("{\"name\":\"again\",\"host\":\"A.example.com\"}")
        .post(PATH)
        .then()
        .statusCode(409);
    given()
        .contentType("application/json")
        .body("{\"name\":\"a\",\"host\":\"b.example.com\"}")
        .put(PATH + "/" + id)
        .then()
        .statusCode(409);
    given()
        .contentType("application/json")
        .body("{\"name\":\"a\",\"host\":\"a.example.com\"}")
        .put(PATH + "/999999")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void templatesAddTheMissingHostsOnce() {
    given()
        .get(PATH + "/templates")
        .then()
        .statusCode(200)
        .body("id", hasItem("maven"))
        .body("find { it.id == 'all' }.hosts", equalTo(List.of("*")));
    create("mine", "repo1.maven.org");
    given()
        .post(PATH + "/templates/maven")
        .then()
        .statusCode(200)
        .body("size()", equalTo(2))
        .body("find { it.host == 'repo.maven.apache.org' }.name", equalTo("Maven Central"))
        .body("find { it.host == 'repo1.maven.org' }.name", equalTo("mine"));
    given().post(PATH + "/templates/maven").then().statusCode(200).body("size()", equalTo(2));
    given().post(PATH + "/templates/nope").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void limitsTheNumberOfHosts() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          for (int i = 0; i < NetworkAllowlist.MAX_ENTRIES; i++) {
            NetworkHost h = new NetworkHost();
            h.name = "h" + i;
            h.host = "h" + i + ".example.com";
            h.createdBy = "test";
            h.createdAt = java.time.Instant.now();
            h.persist();
          }
        });
    given()
        .contentType("application/json")
        .body("{\"name\":\"one more\",\"host\":\"more.example.com\"}")
        .post(PATH)
        .then()
        .statusCode(400);
    given().post(PATH + "/templates/npm").then().statusCode(400);
  }

  @Test
  void invalidStoredHostFailsClosed() {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          NetworkHost h = new NetworkHost();
          h.name = "bad";
          h.host = "10.0.0.1";
          h.createdBy = "test";
          h.createdAt = java.time.Instant.now();
          h.persist();
        });
    assertThrows(
        IllegalArgumentException.class,
        () -> QuarkusTransaction.requiringNew().call(NetworkHost::allowlist));
  }
}
