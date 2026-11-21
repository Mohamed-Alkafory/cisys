/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the {@code details} map of an audit event. This is the last line of defence for sensitive
 * data: keys that name credentials, secrets, request bodies, build output or image data are
 * dropped, long strings are truncated, and image data URLs and JWT-shaped strings are replaced with
 * a marker. Callers must still avoid passing secrets in the first place.
 */
public final class AuditDetails {
  private static final int MAX_STRING = 1000;
  private static final int MAX_ENTRIES = 100;
  private static final int MAX_DEPTH = 4;
  private static final String REDACTED = "[redacted]";

  private static final Set<String> SENSITIVE_KEYS =
      Set.of(
          "password",
          "passwords",
          "token",
          "tokens",
          "accesstoken",
          "refreshtoken",
          "secret",
          "secrets",
          "clientsecret",
          "authorization",
          "cookie",
          "setcookie",
          "body",
          "content",
          "requestbody",
          "responsebody",
          "log",
          "logs",
          "output",
          "command",
          "commands",
          "script",
          "workflow",
          "dockerfile",
          "logo",
          "background",
          "privatekey",
          "apikey",
          "credentials");

  private static final Pattern JWT_ANYWHERE =
      Pattern.compile("[A-Za-z0-9_-]{16,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");

  private static final Pattern DATA_URL = Pattern.compile("data:\\S+", Pattern.CASE_INSENSITIVE);

  private AuditDetails() {}

  /**
   * Removes embedded JWTs and {@code data:} URLs, then caps the length. Used for every free-text
   * field of an event, not only for {@code details}.
   */
  public static String sanitizeText(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String clean = DATA_URL.matcher(text).replaceAll("[redacted binary data]");
    clean = JWT_ANYWHERE.matcher(clean).replaceAll(REDACTED);
    if (clean.length() > MAX_STRING) {
      return clean.substring(0, MAX_STRING) + "…";
    }
    return clean;
  }

  /** A convenient, always-mutable builder. */
  public static Map<String, Object> of(Object... pairs) {
    Map<String, Object> details = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      details.put(String.valueOf(pairs[i]), pairs[i + 1]);
    }
    return details;
  }

  /** A copy of {@code values} with sensitive keys and values removed. */
  public static Map<String, Object> sanitize(Map<String, Object> values) {
    if (values == null || values.isEmpty()) {
      return new LinkedHashMap<>();
    }
    return sanitizeMap(values, 0);
  }

  private static Map<String, Object> sanitizeMap(Map<?, ?> values, int depth) {
    Map<String, Object> clean = new LinkedHashMap<>();
    if (depth > MAX_DEPTH) {
      return clean;
    }
    int count = 0;
    for (Map.Entry<?, ?> entry : values.entrySet()) {
      if (count++ >= MAX_ENTRIES) {
        break;
      }
      String key = entry.getKey() == null ? "" : String.valueOf(entry.getKey());
      if (sensitiveKey(key)) {
        continue;
      }
      Object value = sanitizeValue(entry.getValue(), depth + 1);
      if (value != null) {
        clean.put(key, value);
      }
    }
    return clean;
  }

  private static Object sanitizeValue(Object value, int depth) {
    if (value == null) {
      return null;
    }
    if (value instanceof String text) {
      return sanitizeText(text);
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      return sanitizeMap(map, depth);
    }
    if (value instanceof Iterable<?> iterable) {
      List<Object> clean = new ArrayList<>();
      for (Object item : iterable) {
        if (clean.size() >= MAX_ENTRIES) {
          break;
        }
        Object sanitized = sanitizeValue(item, depth + 1);
        if (sanitized != null) {
          clean.add(sanitized);
        }
      }
      return clean;
    }
    String text = String.valueOf(value);
    return text.length() > MAX_STRING ? text.substring(0, MAX_STRING) + "…" : text;
  }

  private static boolean sensitiveKey(String key) {
    String normalized = key.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    return SENSITIVE_KEYS.contains(normalized)
        || normalized.endsWith("password")
        || normalized.endsWith("secret")
        || normalized.endsWith("token")
        || normalized.equals("credential");
  }
}
