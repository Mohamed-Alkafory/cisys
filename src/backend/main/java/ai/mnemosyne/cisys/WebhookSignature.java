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
  /** GitHub payloads are small; anything larger is rejected before it is parsed. */
  static final int MAX_BYTES = 1024 * 1024;

  private static final HexFormat HEX = HexFormat.of();

  private WebhookSignature() {}

  static boolean valid(String secret, byte[] body, String signature) {
    if (secret == null || secret.isBlank() || body == null || signature == null) return false;
    if (!signature.startsWith("sha256=")) return false;
    String provided = signature.substring("sha256=".length()).strip();
    if (!provided.matches("[0-9a-f]{64}")) return false;
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
