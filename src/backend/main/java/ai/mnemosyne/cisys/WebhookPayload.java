/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;

/** The parts of a GitHub push payload that cisys uses. Everything else is ignored. */
@JsonIgnoreProperties(ignoreUnknown = true)
record WebhookPayload(
    String ref,
    String before,
    String after,
    boolean deleted,
    boolean forced,
    Repository repository,
    Pusher pusher) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Repository(
      @JsonProperty("full_name") String fullName,
      @JsonProperty("clone_url") String cloneUrl,
      @JsonProperty("html_url") String htmlUrl) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record Pusher(String name, String email) {}

  private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
      JsonMapper.builder().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();

  static WebhookPayload parse(byte[] body) throws IOException {
    return MAPPER.readValue(body, WebhookPayload.class);
  }
}
