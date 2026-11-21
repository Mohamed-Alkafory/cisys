/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** Reads the caller and request metadata that every audit event snapshots. */
@ApplicationScoped
public class AuditContext {
  static final String REQUEST_ID_HEADER = "X-Request-Id";

  @Inject SecurityIdentity identity;
  @Inject AuditRequest request;

  /** The authenticated caller, or an anonymous actor when there is no JWT subject. */
  AuditActor actor() {
    String subject = null;
    String username = null;
    if (identity.getPrincipal() instanceof JsonWebToken jwt) {
      subject = jwt.getSubject();
      Object claim = jwt.getClaim("preferred_username");
      if (claim == null) {
        // Test tokens and some providers only carry the standard upn claim.
        claim = jwt.getClaim("upn");
      }
      if (claim != null) {
        username = String.valueOf(claim);
      }
    }
    List<String> roles =
        new TreeSet<>(identity.getRoles())
            .stream().map(role -> role.toLowerCase(Locale.ROOT)).toList();
    return new AuditActor(subject, username, roles);
  }

  String requestId() {
    return clean(request.requestId, 64);
  }

  String method() {
    return clean(request.method, 10);
  }

  String path() {
    return clean(request.path, 512);
  }

  static String clean(String value, int max) {
    if (value == null) {
      return null;
    }
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
  }
}
