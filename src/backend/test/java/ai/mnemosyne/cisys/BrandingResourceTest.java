package ai.mnemosyne.cisys;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class BrandingResourceTest {
    private static final String BRAND = "{\"name\":\"My CI\",\"headerColor\":\"#123456\",\"headingColor\":\"#345678\",\"buttonColor\":\"#567890\"}";
    @Test void anonymousCannotReadBranding() { given().get("/api/branding").then().statusCode(401); }
    @Test @TestSecurity(user = "member", roles = "member")
    void memberCannotUpdate() { given().contentType("application/json").body(BRAND).put("/api/branding").then().statusCode(403); }
    @Test @TestSecurity(user = "lead", roles = "lead")
    void leadCannotUpdate() { given().contentType("application/json").body(BRAND).put("/api/branding").then().statusCode(403); }
    @Test @TestSecurity(user = "admin", roles = "admin")
    void adminCanSaveBranding() {
        given().contentType("application/json").body(BRAND).put("/api/branding").then().statusCode(200).body("name", equalTo("My CI"));
        given().get("/api/branding").then().statusCode(200).body("headerColor", equalTo("#123456"));
        given().get("/api/branding/colors").then().statusCode(200).body("buttonColor", equalTo("#567890"));
    }
    @Test void anonymousCanReadColorsOnly() {
        given().get("/api/branding/colors").then().statusCode(200).body("name", nullValue()).body("logo", nullValue());
    }
    @Test @TestSecurity(user = "admin", roles = "admin")
    void rejectsInvalidColors() { given().contentType("application/json").body(BRAND.replace("#123456", "red")).put("/api/branding").then().statusCode(400); }
}
