package ai.mnemosyne.cisys;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ProjectResourceTest {
    @Test void anonymousCannotListProjects() {
        given().when().get("/api/projects").then().statusCode(401);
    }
    @Test @TestSecurity(user = "member", roles = "member")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "member-id")})
    void memberCannotCreateProject() {
        given().contentType("application/json").body("{\"name\":\"demo\",\"repositoryUrl\":\"https://example.com/repo\"}")
            .when().post("/api/projects").then().statusCode(403);
    }
    @Test @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
    void leadCreatesPrivateProjectWithSubjectOwner() {
        given().contentType("application/json").body("{\"name\":\"demo\",\"repositoryUrl\":\"https://example.com/repo\",\"privateRepository\":true}")
            .when().post("/api/projects").then().statusCode(201).body("ownerSubject", equalTo("lead-id"));
    }
    @Test @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "lead-id")})
    void rejectsDuplicateNameOrRepositoryUrl() {
        given().contentType("application/json").body("{\"name\":\"unique-one\",\"repositoryUrl\":\"https://example.com/unique-one\"}")
            .when().post("/api/projects").then().statusCode(201);
        given().contentType("application/json").body("{\"name\":\" Unique-One \",\"repositoryUrl\":\"https://example.com/other\"}")
            .when().post("/api/projects").then().statusCode(409).body("error", containsString("name"));
        given().contentType("application/json").body("{\"name\":\"unique-two\",\"repositoryUrl\":\"https://example.com/unique-one\"}")
            .when().post("/api/projects").then().statusCode(409).body("error", containsString("repository URL"));
    }
    @Test @TestSecurity(user = "outsider", roles = "unrelated")
    @JwtSecurity(claims = {@Claim(key = "sub", value = "outsider-id")})
    void unrelatedRoleCannotReadProjects() {
        given().when().get("/api/projects").then().statusCode(403);
    }
}
