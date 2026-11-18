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
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class UserResourceTest {
  private Long leadProject;
  private Long otherProject;

  @BeforeEach
  void setUp() {
    clean();
    TestUserDirectory.add("admin-id", "admin", "admin");
    TestUserDirectory.add("lead-id", "lead", "lead");
    TestUserDirectory.add("other-lead-id", "other", "lead");
    TestUserDirectory.add("member-id", "member", "member");
    TestUserDirectory.add("member2-id", "member2", "member");
    leadProject = project("alpha", "lead-id");
    otherProject = project("beta", "other-lead-id");
    assign("member-id", leadProject);
    assign("member2-id", otherProject);
  }

  @AfterEach
  void clean() {
    TestUserDirectory.reset();
    QuarkusTransaction.requiringNew()
        .run(() -> {
          ProjectMember.deleteAll();
          Build.deleteAll();
          Project.deleteAll();
        });
  }

  private static Long project(String name, String owner) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          p.name = name;
          p.repositoryUrl = "https://github.com/org/" + name;
          p.privateRepository = true;
          p.ownerSubject = owner;
          p.persist();
          return p.id;
        });
  }

  private static void assign(String subject, Long projectId) {
    QuarkusTransaction.requiringNew()
        .run(() -> ProjectMember.replace(subject, List.of(projectId), List.of(projectId)));
  }

  private static boolean assigned(String subject, Long projectId) {
    return QuarkusTransaction.requiringNew().call(() -> ProjectMember.isMember(projectId, subject));
  }

  private static String body(String username, String role, Long... projects) {
    return "{\"username\":\""
        + username
        + "\",\"password\":\"Secret123\",\"role\":"
        + (role == null ? "null" : "\"" + role + "\"")
        + ",\"projectIds\":"
        + List.of(projects)
        + "}";
  }

  @Test
  void anonymousIsUnauthorized() {
    given().when().get("/api/users").then().statusCode(401);
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "member-id")})
  void memberCannotManageUsers() {
    given().when().get("/api/users").then().statusCode(403);
    given()
        .contentType("application/json")
        .body(body("new", "member", leadProject))
        .when()
        .post("/api/users")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminCreatesAnyRoleAndAssignsProjects() {
    String id =
        given()
            .contentType("application/json")
            .body(body("New.Lead", "lead", otherProject))
            .when()
            .post("/api/users")
            .then()
            .statusCode(201)
            .body("username", equalTo("new.lead"), "role", equalTo("lead"))
            .body("projects[0].name", equalTo("beta"))
            .extract()
            .path("id");
    assertEquals("lead", TestUserDirectory.ROLES_BY_ID.get(id));
    assertTrue(assigned(id, otherProject));

    given()
        .contentType("application/json")
        .body(body("boss", "admin"))
        .when()
        .post("/api/users")
        .then()
        .statusCode(201)
        .body("role", equalTo("admin"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminListsAndFiltersEveryone() {
    given().when().get("/api/users").then().statusCode(200).body("total", equalTo(5));
    given()
        .when()
        .get("/api/users?role=lead")
        .then()
        .statusCode(200)
        .body("total", equalTo(2), "users.username", contains("lead", "other"));
    given()
        .when()
        .get("/api/users?project=" + otherProject)
        .then()
        .statusCode(200)
        .body("users.username", contains("member2"));
    given().when().get("/api/users?max=2").then().body("users.size()", equalTo(2));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminCannotRemoveTheirOwnAccess() {
    given()
        .contentType("application/json")
        .body("{\"role\":\"member\"}")
        .when()
        .put("/api/users/admin-id")
        .then()
        .statusCode(400);
    given()
        .contentType("application/json")
        .body("{\"enabled\":false}")
        .when()
        .put("/api/users/admin-id")
        .then()
        .statusCode(400);
    given().when().delete("/api/users/admin-id").then().statusCode(400);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminChangesRoleAndDeletes() {
    given()
        .contentType("application/json")
        .body("{\"role\":\"lead\",\"projectIds\":[" + otherProject + "]}")
        .when()
        .put("/api/users/member-id")
        .then()
        .statusCode(200)
        .body("role", equalTo("lead"), "projects.name", contains("beta"));
    assertFalse(assigned("member-id", leadProject));

    given().when().delete("/api/users/member-id").then().statusCode(204);
    assertFalse(TestUserDirectory.USERS.containsKey("member-id"));
    assertFalse(assigned("member-id", otherProject));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void duplicateUsernameAndRejectedPassword() {
    given()
        .contentType("application/json")
        .body(body("member", "member"))
        .when()
        .post("/api/users")
        .then()
        .statusCode(409);
    given()
        .contentType("application/json")
        .body("{\"username\":\"fresh\",\"password\":\"weak\"}")
        .when()
        .post("/api/users")
        .then()
        .statusCode(400)
        .body("error", equalTo("Password policy not met"));
    given()
        .contentType("application/json")
        .body(body("bad name", "member"))
        .when()
        .post("/api/users")
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadCreatesMembersInTheirProjectsOnly() {
    String id =
        given()
            .contentType("application/json")
            .body(body("newbie", null, leadProject))
            .when()
            .post("/api/users")
            .then()
            .statusCode(201)
            .body("role", equalTo("member"))
            .extract()
            .path("id");
    assertTrue(assigned(id, leadProject));

    given()
        .contentType("application/json")
        .body(body("boss", "lead", leadProject))
        .when()
        .post("/api/users")
        .then()
        .statusCode(403);
    given()
        .contentType("application/json")
        .body(body("intruder", "member", otherProject))
        .when()
        .post("/api/users")
        .then()
        .statusCode(403);
    given()
        .contentType("application/json")
        .body(body("loner", "member"))
        .when()
        .post("/api/users")
        .then()
        .statusCode(400);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadSeesTheirProjectsAndMembers() {
    given()
        .when()
        .get("/api/users/projects")
        .then()
        .statusCode(200)
        .body("name", contains("alpha"));
    given()
        .when()
        .get("/api/users")
        .then()
        .statusCode(200)
        .body("users.username", contains("member"), "users[0].projects.name", contains("alpha"));
    // Searching finds members of other projects, without revealing those projects.
    given()
        .when()
        .get("/api/users?search=member2")
        .then()
        .statusCode(200)
        .body("users.username", contains("member2"), "users[0].projects", empty());
    given().when().get("/api/users?project=" + otherProject).then().statusCode(404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadChangesOnlyTheirOwnProjectsOfMembers() {
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[" + leadProject + "],\"role\":\"admin\"}")
        .when()
        .put("/api/users/member2-id")
        .then()
        .statusCode(200)
        .body("role", equalTo("member"));
    assertTrue(assigned("member2-id", leadProject));
    assertTrue(assigned("member2-id", otherProject));

    given()
        .contentType("application/json")
        .body("{\"projectIds\":[]}")
        .when()
        .put("/api/users/member2-id")
        .then()
        .statusCode(200);
    assertFalse(assigned("member2-id", leadProject));
    assertTrue(assigned("member2-id", otherProject));

    given()
        .contentType("application/json")
        .body("{\"projectIds\":[" + otherProject + "]}")
        .when()
        .put("/api/users/member-id")
        .then()
        .statusCode(403);
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[" + leadProject + "]}")
        .when()
        .put("/api/users/admin-id")
        .then()
        .statusCode(404);
    given().when().delete("/api/users/member-id").then().statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadResetsPasswordsOfTheirMembersOnly() {
    given()
        .contentType("application/json")
        .body("{\"password\":\"Temp1234\"}")
        .when()
        .put("/api/users/member-id/password")
        .then()
        .statusCode(204);
    assertEquals("Temp1234", TestUserDirectory.PASSWORDS.get("member-id"));
    given()
        .contentType("application/json")
        .body("{\"password\":\"Temp1234\"}")
        .when()
        .put("/api/users/member2-id/password")
        .then()
        .statusCode(404);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
  void leadCannotTouchUsersWithoutACisysRole() {
    TestUserDirectory.add("norole-id", "norole", null);
    assign("norole-id", leadProject);
    given()
        .when()
        .get("/api/users")
        .then()
        .body("users.find { it.username == 'norole' }.manageable", equalTo(false));
    given()
        .contentType("application/json")
        .body("{\"projectIds\":[]}")
        .when()
        .put("/api/users/norole-id")
        .then()
        .statusCode(404);
    given()
        .contentType("application/json")
        .body("{\"password\":\"Temp1234\"}")
        .when()
        .put("/api/users/norole-id/password")
        .then()
        .statusCode(404);
    assertFalse(TestUserDirectory.PASSWORDS.containsKey("norole-id"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
  void adminEditsUserWithoutACisysRole() {
    TestUserDirectory.add("norole-id", "norole", null);
    given()
        .contentType("application/json")
        .body("{\"email\":\"n@example.com\"}")
        .when()
        .put("/api/users/norole-id")
        .then()
        .statusCode(200)
        .body("email", equalTo("n@example.com"), "role", nullValue());
    assertNull(TestUserDirectory.ROLES_BY_ID.get("norole-id"));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = {@Claim(key = "sub", value = "member-id")})
  void assignedMemberSeesPrivateProject() {
    given().when().get("/api/projects").then().statusCode(200).body("name", contains("alpha"));
    given().when().get("/api/projects/" + leadProject + "/builds").then().statusCode(200);
    given().when().get("/api/projects/" + otherProject + "/builds").then().statusCode(404);
  }
}
