/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class SettingsResourceTest {
    private static final String PATH = "/api/settings/retention";
    static final String DEFAULTS = "{\"maxAgeDays\":30,\"maxBuilds\":50}";

    @Test void anonymousCannotRead() { given().get(PATH).then().statusCode(401); }

    @Test @TestSecurity(user = "lead", roles = "lead")
    void leadCannotReadOrUpdate() {
        given().get(PATH).then().statusCode(403);
        given().contentType("application/json").body(DEFAULTS).put(PATH).then().statusCode(403);
    }

    @Test @TestSecurity(user = "member", roles = "member")
    void memberCannotUpdate() { given().contentType("application/json").body(DEFAULTS).put(PATH).then().statusCode(403); }

    @Test @TestSecurity(user = "admin", roles = "admin")
    void adminSavesRetention() {
        try {
            given().contentType("application/json").body("{\"maxAgeDays\":7,\"maxBuilds\":0}").put(PATH).then().statusCode(200)
                    .body("maxAgeDays", equalTo(7), "maxBuilds", equalTo(0));
            given().get(PATH).then().statusCode(200).body("maxAgeDays", equalTo(7), "maxBuilds", equalTo(0));
        } finally {
            given().contentType("application/json").body(DEFAULTS).put(PATH).then().statusCode(200);
        }
        given().get(PATH).then().statusCode(200).body("maxAgeDays", equalTo(30), "maxBuilds", equalTo(50));
    }

    @Test @TestSecurity(user = "admin", roles = "admin")
    void rejectsInvalidLimits() {
        for (String body : new String[] {"{\"maxAgeDays\":-1,\"maxBuilds\":50}", "{\"maxAgeDays\":30,\"maxBuilds\":-1}",
                "{\"maxAgeDays\":3651,\"maxBuilds\":50}", "{\"maxAgeDays\":30,\"maxBuilds\":10001}", "{\"maxAgeDays\":30}", "{}"}) {
            given().contentType("application/json").body(body).put(PATH).then().statusCode(400);
        }
    }
}
