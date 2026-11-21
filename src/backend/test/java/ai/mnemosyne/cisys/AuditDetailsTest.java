/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditDetailsTest {
  @Test
  void keepsAllowedScalars() {
    Map<String, Object> details =
        AuditDetails.sanitize(
            AuditDetails.of("branch", "main", "attempt", 3, "enabled", true, "empty", null));
    assertEquals("main", details.get("branch"));
    assertEquals(3, details.get("attempt"));
    assertEquals(true, details.get("enabled"));
    assertFalse(details.containsKey("empty"));
  }

  @Test
  void dropsSensitiveKeysRegardlessOfCaseAndSeparators() {
    Map<String, Object> details =
        AuditDetails.sanitize(
            AuditDetails.of(
                "password",
                "hunter2",
                "client_secret",
                "s",
                "accessToken",
                "t",
                "Authorization",
                "Bearer x",
                "request-body",
                "{...}",
                "output",
                "log output",
                "logo",
                "data:image/png;base64,AAAA",
                "script",
                "rm -rf /",
                "projectPassword",
                "p"));
    assertTrue(details.isEmpty(), details.toString());
  }

  @Test
  void redactsBinaryDataAndJwtShapedStrings() {
    Map<String, Object> details =
        AuditDetails.sanitize(
            AuditDetails.of(
                "logo",
                "data:image/png;base64,AAAA",
                "note",
                "data:text/plain,secret",
                "tokenish",
                "aaaa.bbbb.cccc",
                "jwt",
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"));
    assertFalse(details.containsKey("logo"));
    assertEquals("[redacted binary data]", details.get("note"));
    assertEquals("aaaa.bbbb.cccc", details.get("tokenish"));
    assertEquals("[redacted]", details.get("jwt"));
  }

  @Test
  void redactsEmbeddedJwtAndDataUrls() {
    Map<String, Object> details =
        AuditDetails.sanitize(
            AuditDetails.of(
                "note",
                "before eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0."
                    + "dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U after",
                "inline",
                "see data:text/plain,secret now"));
    assertFalse(((String) details.get("note")).contains("eyJhbGci"));
    assertTrue(((String) details.get("note")).contains("[redacted]"));
    assertFalse(((String) details.get("inline")).contains("data:text/plain"));
    assertTrue(((String) details.get("inline")).contains("[redacted binary data]"));
  }

  @Test
  void truncatesLongStringsAndLimitsCollections() {
    String longText = "x".repeat(1500);
    List<Object> many = java.util.stream.IntStream.range(0, 200).mapToObj(i -> (Object) i).toList();
    Map<String, Object> details =
        AuditDetails.sanitize(AuditDetails.of("text", longText, "items", many));
    String text = (String) details.get("text");
    assertEquals(1001, text.length());
    assertTrue(text.endsWith("…"));
    assertEquals(100, ((List<?>) details.get("items")).size());
  }

  @Test
  void sanitizesNestedMaps() {
    Map<String, Object> details =
        AuditDetails.sanitize(
            AuditDetails.of(
                "nested", AuditDetails.of("password", "secret", "kept", "value", "token", "t")));
    @SuppressWarnings("unchecked")
    Map<String, Object> nested = (Map<String, Object>) details.get("nested");
    assertEquals(Map.of("kept", "value"), nested);
  }
}
