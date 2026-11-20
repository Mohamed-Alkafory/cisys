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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ArticleResourceTest {
  private static final String PATH = "/api/articles";

  private static Long article(String title) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Article a = new Article();
          a.title = title;
          a.body = "# Hello";
          a.authorSubject = "admin-id";
          a.createdAt = Instant.now();
          a.updatedAt = a.createdAt;
          a.persist();
          return a.id;
        });
  }

  private static Article stored(Long id) {
    return QuarkusTransaction.requiringNew().call(() -> Article.findById(id));
  }

  static void editors(Settings.ArticleEditors editors) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          Settings s = Settings.findById(1L);
          if (s == null) {
            s = new Settings();
            s.persist();
          }
          s.articleEditors = editors;
        });
  }

  private static String body(String title, String text) {
    return "{\"title\":\"" + title + "\",\"body\":\"" + text + "\"}";
  }

  @AfterEach
  void resetEditors() {
    editors(Settings.ArticleEditors.ADMIN);
  }

  @Test
  void anonymousReads() {
    TestUserDirectory.add("admin-id", "alice", "admin");
    String title = "FAQ " + UUID.randomUUID();
    Long id = article(title);
    given()
        .get(PATH)
        .then()
        .statusCode(200)
        .body("canEdit", equalTo(false))
        .body("articles.title", hasItem(title));
    given()
        .get(PATH + "/" + id)
        .then()
        .statusCode(200)
        .body("title", equalTo(title))
        .body("body", equalTo("# Hello"))
        .body("author", equalTo("alice"))
        .body("canEdit", equalTo(false));
  }

  @Test
  void anonymousCannotWrite() {
    Long id = article("FAQ");
    given().contentType("application/json").body(body("a", "b")).post(PATH).then().statusCode(401);
    given()
        .contentType("application/json")
        .body(body("a", "b"))
        .put(PATH + "/" + id)
        .then()
        .statusCode(401);
    given().delete(PATH + "/" + id).then().statusCode(401);
  }

  @Test
  void missingArticleIs404() {
    given().get(PATH + "/999999999").then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void memberCannotWriteByDefault() {
    Long id = article("FAQ");
    given().get(PATH).then().statusCode(200).body("canEdit", equalTo(false));
    given().contentType("application/json").body(body("a", "b")).post(PATH).then().statusCode(403);
    given()
        .contentType("application/json")
        .body(body("a", "b"))
        .put(PATH + "/" + id)
        .then()
        .statusCode(403);
    given().delete(PATH + "/" + id).then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadWritesWhenLeadsMay() {
    given().contentType("application/json").body(body("a", "b")).post(PATH).then().statusCode(403);
    editors(Settings.ArticleEditors.LEAD);
    given().contentType("application/json").body(body("a", "b")).post(PATH).then().statusCode(201);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void memberWritesWhenMembersMay() {
    editors(Settings.ArticleEditors.MEMBER);
    TestUserDirectory.add("member-id", "maria", "member");
    given().get(PATH).then().statusCode(200).body("canEdit", equalTo(true));
    given()
        .contentType("application/json")
        .body(body("  Setup  ", "Steps"))
        .post(PATH)
        .then()
        .statusCode(201)
        .body("title", equalTo("Setup"))
        .body("author", equalTo("maria"))
        .body("canEdit", equalTo(true));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCreatesEditsAndDeletes() {
    Integer id =
        given()
            .contentType("application/json")
            .body(body("FAQ", "Question"))
            .post(PATH)
            .then()
            .statusCode(201)
            .extract()
            .path("id");
    given()
        .contentType("application/json")
        .body(body("FAQ 2", "Answer"))
        .put(PATH + "/" + id)
        .then()
        .statusCode(200)
        .body("title", equalTo("FAQ 2"))
        .body("body", equalTo("Answer"));
    Article a = stored(id.longValue());
    assertEquals("FAQ 2", a.title);
    assertEquals("admin-id", a.authorSubject);
    given().delete(PATH + "/" + id).then().statusCode(204);
    assertNull(stored(id.longValue()));
    given().delete(PATH + "/" + id).then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void blankOrTooLongIs400() {
    given().contentType("application/json").body(body(" ", "b")).post(PATH).then().statusCode(400);
    given().contentType("application/json").body(body("a", " ")).post(PATH).then().statusCode(400);
    given()
        .contentType("application/json")
        .body(body("x".repeat(Article.MAX_TITLE + 1), "b"))
        .post(PATH)
        .then()
        .statusCode(400)
        .body(
            "error", equalTo("A title of up to " + Article.MAX_TITLE + " characters is required"));
    given().contentType("application/json").body("{}").post(PATH).then().statusCode(400);
  }
}
