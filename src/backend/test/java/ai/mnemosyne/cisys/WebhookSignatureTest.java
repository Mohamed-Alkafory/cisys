/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** The HMAC check is the only authentication of a webhook delivery, so its edges matter. */
class WebhookSignatureTest {
  private static final String SECRET = "test-webhook-secret-0123456789";
  private static final byte[] BODY =
      "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);

  private static String sign(String secret, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
  }

  @Test
  void acceptsTheSignatureGithubSends() throws Exception {
    assertTrue(WebhookSignature.valid(SECRET, BODY, sign(SECRET, BODY)));
  }

  @Test
  void rejectsATamperedBody() throws Exception {
    String signature = sign(SECRET, BODY);
    byte[] tampered = "{\"ref\":\"refs/heads/main \"}".getBytes(StandardCharsets.UTF_8);
    assertFalse(WebhookSignature.valid(SECRET, tampered, signature));
    assertFalse(WebhookSignature.valid(SECRET, new byte[0], signature));
  }

  @Test
  void rejectsASignatureMadeWithAnotherSecret() throws Exception {
    assertFalse(WebhookSignature.valid("another-secret", BODY, sign(SECRET, BODY)));
    assertFalse(WebhookSignature.valid(SECRET, BODY, sign("another-secret", BODY)));
  }

  @Test
  void rejectsMissingGarbageAndShortSignatures() {
    String zeros = "0".repeat(64);
    assertFalse(WebhookSignature.valid(SECRET, BODY, null));
    assertFalse(WebhookSignature.valid(SECRET, BODY, ""));
    assertFalse(WebhookSignature.valid(SECRET, BODY, "garbage"));
    assertFalse(WebhookSignature.valid(SECRET, BODY, "sha256"));
    assertFalse(WebhookSignature.valid(SECRET, BODY, "sha256="));
    assertFalse(WebhookSignature.valid(SECRET, BODY, "sha256=" + "0".repeat(63)));
    assertFalse(WebhookSignature.valid(SECRET, BODY, "sha256=" + "0".repeat(65)));
    assertFalse(WebhookSignature.valid(SECRET, BODY, "sha256=" + zeros + "x"));
  }

  @Test
  void rejectsUppercaseHexNonHexAndAnotherPrefix() throws Exception {
    String signature = sign(SECRET, BODY);
    String digest = signature.substring("sha256=".length());
    assertFalse(WebhookSignature.valid(SECRET, BODY, "sha256=" + digest.toUpperCase(Locale.ROOT)));
    assertFalse(WebhookSignature.valid(SECRET, BODY, "sha256=" + "g".repeat(64)));
    assertFalse(WebhookSignature.valid(SECRET, BODY, "sha1=" + digest));
    assertFalse(WebhookSignature.valid(SECRET, BODY, "SHA256=" + digest));
  }

  @Test
  void rejectsNullAndBlankInputs() {
    assertFalse(WebhookSignature.valid(null, BODY, "sha256=" + "0".repeat(64)));
    assertFalse(WebhookSignature.valid("", BODY, "sha256=" + "0".repeat(64)));
    assertFalse(WebhookSignature.valid("   ", BODY, "sha256=" + "0".repeat(64)));
    assertFalse(WebhookSignature.valid(SECRET, null, "sha256=" + "0".repeat(64)));
    assertFalse(WebhookSignature.valid(SECRET, BODY, null));
  }

  @Test
  void acceptsTheDigestWithSurroundingWhitespaceAfterThePrefix() throws Exception {
    String signature = sign(SECRET, BODY);
    String digest = signature.substring("sha256=".length());
    assertTrue(WebhookSignature.valid(SECRET, BODY, "sha256= " + digest + " "));
  }

  @Test
  void wellFormedChecksTheShapeWithoutTheSecret() throws Exception {
    assertTrue(WebhookSignature.wellFormed(sign(SECRET, BODY)));
    assertTrue(WebhookSignature.wellFormed("sha256= " + "0".repeat(64) + " "));
    assertFalse(WebhookSignature.wellFormed(null));
    assertFalse(WebhookSignature.wellFormed("sha256="));
    assertFalse(WebhookSignature.wellFormed("sha256=" + "0".repeat(63)));
    assertFalse(WebhookSignature.wellFormed("sha256=" + "0".repeat(64) + "x"));
    assertFalse(WebhookSignature.wellFormed("sha1=" + "0".repeat(40)));
    assertFalse(WebhookSignature.wellFormed("0".repeat(64)));
  }

  @Test
  void secretIsAnUnpaddedBase64UrlValueWith256Bits() {
    String secret = WebhookSignature.secret();
    assertEquals(43, secret.length());
    assertTrue(secret.matches("[A-Za-z0-9_-]{43}"));
    assertEquals(32, Base64.getUrlDecoder().decode(secret).length);

    Set<String> unique = new HashSet<>();
    for (int i = 0; i < 100; i++) assertTrue(unique.add(WebhookSignature.secret()));
  }
}
