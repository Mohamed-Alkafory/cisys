/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** GitHub's {@code X-Hub-Signature-256} over the raw webhook body. */
final class WebhookSignature {
  private static final HexFormat HEX = HexFormat.of();

  /**
   * A constant used to hash a delivery for an unknown or disabled project, so those answers take
   * the same path as a bad signature and do not reveal which projects exist.
   */
  static final String DUMMY_SECRET = "cisys-webhook-placeholder-secret-0123456789";

  private WebhookSignature() {}

  /** Whether the header has the shape GitHub sends, checked before the body is read. */
  static boolean wellFormed(String signature) {
    if (signature == null || !signature.startsWith("sha256=")) return false;
    return signature.substring("sha256=".length()).strip().matches("[0-9a-f]{64}");
  }

  static boolean valid(String secret, byte[] body, String signature) {
    if (secret == null || secret.isBlank() || body == null || !wellFormed(signature)) return false;
    String provided = signature.substring("sha256=".length()).strip();
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String expected = HEX.formatHex(mac.doFinal(body));
      return MessageDigest.isEqual(
          expected.getBytes(StandardCharsets.US_ASCII),
          provided.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException e) {
      return false;
    }
  }

  /** A random URL-safe secret; 32 bytes give 256 bits of entropy. */
  static String secret() {
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
