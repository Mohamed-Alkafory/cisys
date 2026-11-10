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
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(BranchTestProfile.class)
class PublicBranchResourceTest {
    private static final String GITHUB = "https://github.com/octocat/Hello-World";

    private static String github() {
        return GITHUB + "-" + UUID.randomUUID() + ".git";
    }

    private static String unreachable() {
        return "https://github.com/octocat/unreachable-" + UUID.randomUUID() + ".git";
    }

    private String project(boolean priv, String repositoryUrl) {
        return QuarkusTransaction.requiringNew().call(() -> {
            String name = "Public-Page-" + UUID.randomUUID();
            Project p = new Project();
            p.name = name;
            p.repositoryUrl = repositoryUrl;
            p.ownerSubject = "owner-id";
            p.privateRepository = priv;
            p.persist();
            return name;
        });
    }

    @Test
    void anonymousSeesBranchesOfPublicProject() {
        String name = project(false, github());
        TestGitRepositoryService.resetCalls();
        given().get("/api/project-pages/" + name.toLowerCase() + "/branches").then()
                .statusCode(200)
                .body("branches", hasSize(2))
                .body("branches", contains("dev", "main"))
                .body("reason", nullValue());
        assertEquals(1, TestGitRepositoryService.calls());
    }

    @Test
    void privateProjectIsNotFound() {
        String name = project(true, github());
        TestGitRepositoryService.resetCalls();
        given().get("/api/project-pages/" + name + "/branches").then().statusCode(404);
        assertEquals(0, TestGitRepositoryService.calls());
    }

    @Test
    @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerCannotUseThePublicEndpointForAPrivateProject() {
        String name = project(true, github());
        TestGitRepositoryService.resetCalls();
        given().get("/api/project-pages/" + name + "/branches").then().statusCode(404);
        assertEquals(0, TestGitRepositoryService.calls());
    }

    @Test
    @TestSecurity(user = "admin", roles = "admin")
    @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
    void adminCannotUseThePublicEndpointForAPrivateProject() {
        String name = project(true, github());
        given().get("/api/project-pages/" + name + "/branches").then().statusCode(404);
    }

    @Test
    void unknownProjectIsNotFound() {
        given().get("/api/project-pages/does-not-exist-" + UUID.randomUUID() + "/branches").then().statusCode(404);
    }

    @Test
    void unreachableRepositoryIsReportedAsBadGateway() {
        String name = project(false, unreachable());
        given().get("/api/project-pages/" + name + "/branches").then()
                .statusCode(502)
                .body("error", equalTo("Unable to read branches from the repository"));
    }

    @Test
    void invalidRepositoryUrlIsRejected() {
        String name = project(false, "https://example.com/octocat/Hello-World.git");
        given().get("/api/project-pages/" + name + "/branches").then()
                .statusCode(400)
                .body("error", equalTo("Use an HTTPS github.com or gitlab.com repository URL"));
    }
}
