/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import io.restassured.http.ContentType;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TeamTestProfile.class)
class ProjectTeamResourceTest {

  @BeforeEach
  void users() {
    TestUserDirectory.reset();
    TestUserDirectory.add("lead-id", "lead", "lead");
    TestUserDirectory.add("member-id", "member", "member");
    TestUserDirectory.add("other-id", "other", "lead");
  }

  /** Creates a fresh project with {@code owner} as its LEAD. */
  private Long project(String owner, boolean priv) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = new Project();
          String n = UUID.randomUUID().toString();
          p.name = "members-" + n;
          p.repositoryUrl = "https://github.com/octocat/members-" + n + ".git";
          p.ownerSubject = owner;
          p.privateRepository = priv;
          p.persist();
          ProjectMember m = new ProjectMember();
          m.projectId = p.id;
          m.subject = owner;
          m.role = ProjectMember.TeamRole.LEAD;
          m.createdAt = java.time.Instant.now();
          m.persist();
          return p.id;
        });
  }

  private static void membership(Long projectId, String subject, ProjectMember.TeamRole role) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          ProjectMember m = new ProjectMember();
          m.projectId = projectId;
          m.subject = subject;
          m.role = role;
          m.createdAt = java.time.Instant.now();
          m.persist();
        });
  }

  private static long roleCount(Long projectId) {
    return QuarkusTransaction.requiringNew()
        .call(() -> ProjectMember.count("projectId = ?1", projectId));
  }

  private static ProjectMember.TeamRole roleOf(Long projectId, String subject) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            ProjectMember.<ProjectMember>find("projectId = ?1 and subject = ?2", projectId, subject)
                .firstResult()
                .role);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void listShowsMembers() {
    Long id = project("lead-id", false);
    membership(id, "member-id", ProjectMember.TeamRole.MEMBER);
    given()
        .get("/api/projects/" + id + "/team")
        .then()
        .statusCode(200)
        .body("$", hasSize(2))
        .body("find { it.username == 'lead' }.role", equalTo("LEAD"))
        .body("find { it.username == 'member' }.role", equalTo("MEMBER"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void nonMemberGetsNotFound() {
    Long id = project("lead-id", true);
    given()
        .get("/api/projects/" + id + "/team")
        .then()
        .statusCode(404)
        .body(org.hamcrest.Matchers.equalTo(""));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void unknownUserIs404WithJsonError() {
    Long id = project("lead-id", false);
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"ghost\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + id + "/team")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("error", equalTo("No user named 'ghost' exists in Keycloak"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void demoteOfMemberNotInProjectIs404WithJsonError() {
    Long id = project("lead-id", false);
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/projects/" + id + "/team/ghost")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("error", equalTo("'ghost' is not a member of this project"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void removeOfKnownUserNotInProjectIs404WithJsonError() {
    Long id = project("lead-id", false);
    given()
        .contentType(ContentType.JSON)
        .delete("/api/projects/" + id + "/team/member")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("error", equalTo("'member' is not a member of this project"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void missingUsernameIs400WithJsonError() {
    Long id = project("lead-id", false);
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + id + "/team")
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("error", equalTo("username is required"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminSeesMembersWithoutMembership() {
    Long id = project("lead-id", true);
    given().get("/api/projects/" + id + "/team").then().statusCode(200).body("$", hasSize(1));
  }

  @Test
  @TestSecurity(user = "member", roles = "member")
  @JwtSecurity(claims = @Claim(key = "sub", value = "member-id"))
  void memberOfProjectCannotAddMembers() {
    Long id = project("lead-id", false);
    membership(id, "member-id", ProjectMember.TeamRole.MEMBER);
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"other\",\"role\":\"LEAD\"}")
        .post("/api/projects/" + id + "/team")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void leadOutsideProjectCannotAdd() {
    Long id = project("lead-id", false);
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"member\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + id + "/team")
        .then()
        .statusCode(403);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadAddsMember() {
    Long id = project("lead-id", false);
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"member\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + id + "/team")
        .then()
        .statusCode(201)
        .body("username", equalTo("member"), "role", equalTo("MEMBER"));
    assertEquals(2, roleCount(id));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadPromotesMember() {
    Long id = project("lead-id", false);
    membership(id, "member-id", ProjectMember.TeamRole.MEMBER);
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"LEAD\"}")
        .put("/api/projects/" + id + "/team/member")
        .then()
        .statusCode(200)
        .body("username", equalTo("member"), "role", equalTo("LEAD"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void lastLeadCannotBeDemotedOrRemoved() {
    Long id = project("lead-id", false);
    membership(id, "other-id", ProjectMember.TeamRole.LEAD);
    // The creator steps out entirely; leave `other-id` as the sole LEAD.
    QuarkusTransaction.requiringNew()
        .run(() -> ProjectMember.delete("projectId = ?1 and subject = ?2", id, "lead-id"));
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/projects/" + id + "/team/other")
        .then()
        .statusCode(409);
    given().delete("/api/projects/" + id + "/team/other").then().statusCode(409);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminAddsMemberToAnyProject() {
    Long id = project("lead-id", true);
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"member\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + id + "/team")
        .then()
        .statusCode(201)
        .body("username", equalTo("member"), "role", equalTo("MEMBER"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminMustRespectLastLead() {
    Long id = project("lead-id", false);
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/projects/" + id + "/team/lead")
        .then()
        .statusCode(409);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminAddsNewMember() {
    Long id = project("lead-id", true);
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"member\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + id + "/team")
        .then()
        .statusCode(201)
        .body("username", equalTo("member"), "role", equalTo("MEMBER"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void duplicateMemberIs409() {
    Long id = project("lead-id", false);
    membership(id, "member-id", ProjectMember.TeamRole.MEMBER);
    given()
        .contentType(ContentType.JSON)
        .body("{\"username\":\"member\",\"role\":\"MEMBER\"}")
        .post("/api/projects/" + id + "/team")
        .then()
        .statusCode(409);
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void leadRemovesMember() {
    Long id = project("lead-id", false);
    membership(id, "member-id", ProjectMember.TeamRole.MEMBER);
    given().delete("/api/projects/" + id + "/team/member").then().statusCode(204);
    assertEquals(1, roleCount(id));
  }

  private static final String CREATOR_ERROR =
      "Only a global admin can demote or remove the project's creator";

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void creatorCannotRemoveThemselves() {
    Long id = project("lead-id", false);
    given()
        .delete("/api/projects/" + id + "/team/lead")
        .then()
        .statusCode(403)
        .contentType(ContentType.JSON)
        .body("error", equalTo(CREATOR_ERROR));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf(id, "lead-id"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void otherLeadCannotRemoveCreator() {
    Long id = project("lead-id", false);
    membership(id, "other-id", ProjectMember.TeamRole.LEAD);
    given()
        .delete("/api/projects/" + id + "/team/lead")
        .then()
        .statusCode(403)
        .contentType(ContentType.JSON)
        .body("error", equalTo(CREATOR_ERROR));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf(id, "lead-id"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "lead-id"))
  void creatorCannotDemoteThemselves() {
    Long id = project("lead-id", false);
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/projects/" + id + "/team/lead")
        .then()
        .statusCode(403)
        .contentType(ContentType.JSON)
        .body("error", equalTo(CREATOR_ERROR));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf(id, "lead-id"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void otherLeadCannotDemoteCreator() {
    Long id = project("lead-id", false);
    membership(id, "other-id", ProjectMember.TeamRole.LEAD);
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/projects/" + id + "/team/lead")
        .then()
        .statusCode(403)
        .contentType(ContentType.JSON)
        .body("error", equalTo(CREATOR_ERROR));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf(id, "lead-id"));
  }

  @Test
  @TestSecurity(user = "lead", roles = "lead")
  @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
  void nonCreatorLeadStillManagesOthers() {
    Long id = project("lead-id", false);
    membership(id, "other-id", ProjectMember.TeamRole.LEAD);
    membership(id, "member-id", ProjectMember.TeamRole.MEMBER);
    // Promote the member, then demote them back as the other lead, then remove them.
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"LEAD\"}")
        .put("/api/projects/" + id + "/team/member")
        .then()
        .statusCode(200)
        .body("role", equalTo("LEAD"));
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/projects/" + id + "/team/member")
        .then()
        .statusCode(200)
        .body("role", equalTo("MEMBER"));
    given().delete("/api/projects/" + id + "/team/member").then().statusCode(204);
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCanDemoteCreator() {
    Long id = project("lead-id", false);
    membership(id, "other-id", ProjectMember.TeamRole.LEAD);
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/projects/" + id + "/team/lead")
        .then()
        .statusCode(200)
        .body("role", equalTo("MEMBER"));
    assertEquals(ProjectMember.TeamRole.MEMBER, roleOf(id, "lead-id"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCanRemoveCreator() {
    Long id = project("lead-id", false);
    membership(id, "other-id", ProjectMember.TeamRole.LEAD);
    given().delete("/api/projects/" + id + "/team/lead").then().statusCode(204);
    assertEquals(1, roleCount(id));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf(id, "other-id"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
  void adminCannotDemoteSoleLeadCreator() {
    Long id = project("lead-id", false);
    given()
        .contentType(ContentType.JSON)
        .body("{\"role\":\"MEMBER\"}")
        .put("/api/projects/" + id + "/team/lead")
        .then()
        .statusCode(409)
        .contentType(ContentType.JSON)
        .body("error", equalTo("A project must keep at least one LEAD; add another LEAD first"));
    assertEquals(ProjectMember.TeamRole.LEAD, roleOf(id, "lead-id"));
  }
}
