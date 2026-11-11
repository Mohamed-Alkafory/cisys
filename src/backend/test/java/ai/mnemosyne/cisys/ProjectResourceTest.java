/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ProjectResourceTest {
    @Test void anonymousCannotListProjects() {
        given().when().get("/api/projects").then().statusCode(401);
    }
    @Test @TestSecurity(user = "member", roles = "member")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "member-id")})
    void memberCannotCreateProject() {
        given().contentType("application/json").body("{\"name\":\"demo\",\"repositoryUrl\":\"https://example.com/repo.git\"}")
            .when().post("/api/projects").then().statusCode(403);
    }
    @Test @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
    void leadCreatesPrivateProjectWithSubjectOwner() {
        given().contentType("application/json").body("{\"name\":\"demo\",\"repositoryUrl\":\"https://example.com/repo.git\",\"privateRepository\":true}")
            .when().post("/api/projects").then().statusCode(201).body("ownerSubject", equalTo("lead-id"));
    }
    @Test @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
    void rejectsDuplicateNameOrRepositoryUrl() {
        given().contentType("application/json").body("{\"name\":\"unique-one\",\"repositoryUrl\":\"https://example.com/unique-one.git\"}")
            .when().post("/api/projects").then().statusCode(201);
        given().contentType("application/json").body("{\"name\":\" Unique-One \",\"repositoryUrl\":\"https://example.com/other.git\"}")
            .when().post("/api/projects").then().statusCode(409).body("error", containsString("name"));
        given().contentType("application/json").body("{\"name\":\"unique-two\",\"repositoryUrl\":\"https://example.com/unique-one.git\"}")
            .when().post("/api/projects").then().statusCode(409).body("error", containsString("repository URL"));
    }
    @Test @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
    void rejectsInvalidNames() {
        for (String name : new String[] {"org/repo", ".", "..", ".hidden", "-x", "_x", "a b", "a?b", "a#b", "a%20b", "a\\nb", "a\\u200Bb", "c\\u0456sys", "\\u00fcn"}) {
            given().contentType("application/json").body("{\"name\":\"" + name + "\",\"repositoryUrl\":\"https://example.com/invalid-name.git\"}")
                .when().post("/api/projects").then().statusCode(400).body("error", equalTo(ProjectResource.NAME_RULE));
        }
    }
    @Test @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
    void acceptsSlugNames() {
        for (String name : new String[] {"slug", "My.Project_2-x", "9lives", "a".repeat(100)}) {
            given().contentType("application/json").body("{\"name\":\"" + name + "\",\"repositoryUrl\":\"https://example.com/slug-" + name + ".git\"}")
                .when().post("/api/projects").then().statusCode(201);
        }
        given().urlEncodingEnabled(false).when().get("/api/project-pages/My.Project_2-x").then().statusCode(200).body("project.name", equalTo("My.Project_2-x"));
        given().contentType("application/json").body("{\"name\":\"" + "a".repeat(101) + "\",\"repositoryUrl\":\"https://example.com/too-long.git\"}")
            .when().post("/api/projects").then().statusCode(400);
    }
    @Test @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
    void addsGitSuffix() {
        given().contentType("application/json").body("{\"name\":\"suffix-added\",\"repositoryUrl\":\"https://example.com/org/suffix-added/\"}")
            .when().post("/api/projects").then().statusCode(201).body("repositoryUrl", equalTo("https://example.com/org/suffix-added.git"));
        given().contentType("application/json").body("{\"name\":\"suffix-kept\",\"repositoryUrl\":\"https://example.com/org/suffix-kept.git\"}")
            .when().post("/api/projects").then().statusCode(201).body("repositoryUrl", equalTo("https://example.com/org/suffix-kept.git"));
        // The same repository with and without .git is a duplicate.
        given().contentType("application/json").body("{\"name\":\"suffix-again\",\"repositoryUrl\":\"https://example.com/org/suffix-kept\"}")
            .when().post("/api/projects").then().statusCode(409);
    }
    @Test @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
    void rejectsUrlsWithoutRepository() {
        for (String url : new String[] {"https://example.com/", "https://example.com", "https://example.com/.git", "https://example.com/repo.git?x=1", "https://example.com/repo#top"}) {
            given().contentType("application/json").body("{\"name\":\"suffix\",\"repositoryUrl\":\"" + url + "\"}")
                .when().post("/api/projects").then().statusCode(400).body("error", containsString("repository's URL"));
        }
    }
    @Test @TestSecurity(user = "outsider", roles = "unrelated")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "outsider-id")})
    void unrelatedRoleCannotReadProjects() {
        given().when().get("/api/projects").then().statusCode(403);
    }
    private static Long project(String owner, boolean priv) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Project p = new Project(); String n = java.util.UUID.randomUUID().toString();
            p.name = "delete-" + n; p.repositoryUrl = "https://example.com/delete-" + n; p.ownerSubject = owner; p.privateRepository = priv;
            p.persist(); return p.id;
        });
    }
    private static Long build(Long project, String status) {
        return QuarkusTransaction.requiringNew().call(() -> {
            Build b = new Build(); b.projectId = project; b.requestedBy = "lead-id"; b.status = status; b.branch = "main";
            b.persist(); return b.id;
        });
    }
    private static boolean exists(Long project) {
        return QuarkusTransaction.requiringNew().call(() -> Project.findById(project) != null);
    }
    @Test @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
    void ownerDeletesProjectAndItsBuilds() {
        Long id = project("lead-id", false);
        Long b = build(id, "PASSED");
        given().when().delete("/api/projects/" + id).then().statusCode(204);
        assertFalse(exists(id));
        assertNull(QuarkusTransaction.requiringNew().call(() -> Build.findById(b)));
    }
    @Test @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
    void leadCannotDeleteOthersProjects() {
        Long open = project("other-id", false), hidden = project("other-id", true);
        given().when().delete("/api/projects/" + open).then().statusCode(403);
        given().when().delete("/api/projects/" + hidden).then().statusCode(404);
        assertTrue(exists(open));
        assertTrue(exists(hidden));
    }
    @Test @TestSecurity(user = "member", roles = "member")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "member-id")})
    void memberCannotDeleteProject() {
        Long id = project("member-id", false);
        given().when().delete("/api/projects/" + id).then().statusCode(403);
        assertTrue(exists(id));
    }
    @Test @TestSecurity(user = "admin", roles = "admin")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "admin-id")})
    void adminDeletesAnyProjectButNotWhileBuilding() {
        Long id = project("other-id", true);
        Long running = build(id, "RUNNING");
        given().when().delete("/api/projects/" + id).then().statusCode(409).body("error", containsString("running"));
        assertTrue(exists(id));
        QuarkusTransaction.requiringNew().run(() -> Build.<Build>findById(running).status = "PASSED");
        given().when().delete("/api/projects/" + id).then().statusCode(204);
        assertFalse(exists(id));
        given().when().delete("/api/projects/" + id).then().statusCode(404);
    }
}
