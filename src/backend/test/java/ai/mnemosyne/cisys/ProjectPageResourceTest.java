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
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class ProjectPageResourceTest {
    @Inject BuildLogs logs;

    record Seeded(String name, long buildId) {}

    /** Creates a project with one finished build and returns its name. */
    private String project(boolean priv) {
        return seed(priv).name();
    }

    private Seeded seed(boolean priv) {
        Seeded seeded = QuarkusTransaction.requiringNew().call(() -> {
            String n = UUID.randomUUID().toString();
            Project p = new Project();
            p.name = "Page-" + n;
            p.repositoryUrl = "https://github.com/octocat/page-" + n + ".git";
            p.ownerSubject = "owner-id";
            p.privateRepository = priv;
            p.persist();
            Build b = new Build();
            b.projectId = p.id;
            b.status = "PASSED";
            b.branch = "main";
            b.requestedBy = "owner-id";
            b.credentialRef = "CISYS_GIT_SECRET";
            b.persist();
            return new Seeded(p.name, b.id);
        });
        byte[] log = "hello".getBytes(StandardCharsets.UTF_8);
        try (var w = logs.open(seeded.buildId())) { w.append(log, 0, log.length); }
        return seeded;
    }

    @Test
    void anonymousSeesPublicProjectBuilds() {
        String name = project(false);
        given().get("/api/project-pages/" + name.toLowerCase()).then().statusCode(200)
                .body("project.name", equalTo(name), "project.privateRepository", equalTo(false),
                        "builds", hasSize(1), "builds[0].status", equalTo("PASSED"), "builds[0].logTruncated", equalTo(false),
                        "builds[0]", not(hasKey("output")),
                        "builds[0]", not(hasKey("requestedBy")), "builds[0]", not(hasKey("credentialRef")));
    }

    @Test
    void anonymousReadsPublicBuildLog() {
        Seeded s = seed(false);
        given().get("/api/project-pages/" + s.name() + "/builds/" + s.buildId() + "/log").then().statusCode(200)
                .contentType(startsWith("text/plain")).body(equalTo("hello"));
    }

    @Test
    void anonymousCannotReadPrivateBuildLog() {
        Seeded s = seed(true);
        given().get("/api/project-pages/" + s.name() + "/builds/" + s.buildId() + "/log").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerReadsPrivateBuildLog() {
        Seeded s = seed(true);
        given().get("/api/project-pages/" + s.name() + "/builds/" + s.buildId() + "/log").then().statusCode(200)
                .body(equalTo("hello"));
    }

    @Test
    void logOfAnotherProjectsBuildIsNotFound() {
        Seeded page = seed(false), other = seed(false);
        given().get("/api/project-pages/" + page.name() + "/builds/" + other.buildId() + "/log").then().statusCode(404);
    }

    @Test
    void anonymousCannotSeePrivateProject() {
        given().get("/api/project-pages/" + project(true)).then().statusCode(404);
    }

    @Test
    void unknownProjectIsNotFound() {
        given().get("/api/project-pages/does-not-exist-" + UUID.randomUUID()).then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "member", roles = "member")
    @JwtSecurity(claims = @Claim(key = "sub", value = "other-id"))
    void otherMemberCannotSeePrivateProject() {
        given().get("/api/project-pages/" + project(true)).then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "lead", roles = "lead")
    @JwtSecurity(claims = @Claim(key = "sub", value = "owner-id"))
    void ownerSeesPrivateProject() {
        given().get("/api/project-pages/" + project(true)).then().statusCode(200).body("builds", hasSize(1));
    }

    @Test
    @TestSecurity(user = "admin", roles = "admin")
    @JwtSecurity(claims = @Claim(key = "sub", value = "admin-id"))
    void adminSeesPrivateProject() {
        given().get("/api/project-pages/" + project(true)).then().statusCode(200);
    }
}
