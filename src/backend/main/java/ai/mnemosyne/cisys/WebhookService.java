/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Validates a GitHub push delivery and turns it into a build. */
@ApplicationScoped
public class WebhookService {
  /** GitHub payloads are small; anything larger is rejected before it is parsed. */
  static final int DEFAULT_MAX_BYTES = 1024 * 1024;

  @Inject BuildRunner runner;

  @ConfigProperty(name = "cisys.webhook.max-bytes", defaultValue = "1048576")
  int maxBytes;

  static final String PUSH = "push";
  static final String SHA = "[0-9a-f]{40}";

  /**
   * Returns a small JSON body for GitHub. The endpoint reads the body through {@link #read}, so a
   * delivery larger than the limit never reaches the parser.
   */
  public Map<String, Object> receive(
      Long projectId, String event, String delivery, String signature, byte[] body) {
    if (body == null || body.length == 0) throw error(400, "The webhook body is empty");
    if (body.length > Math.max(1, maxBytes)) throw error(413, "The webhook body is too large");
    Project project = Project.findById(projectId);
    String secret =
        project == null
                || !project.webhookEnabled
                || project.webhookSecret == null
                || project.webhookSecret.isBlank()
            ? null
            : project.webhookSecret;
    // Hash even when there is no usable secret, so unknown, disabled and unconfigured projects
    // answer exactly like a bad signature and cannot be enumerated.
    boolean authenticated =
        WebhookSignature.valid(
            secret == null ? WebhookSignature.DUMMY_SECRET : secret, body, signature);
    if (secret == null || !authenticated) throw error(401, "Invalid signature");
    if (event == null || event.isBlank()) throw error(400, "Missing X-GitHub-Event");
    if ("ping".equals(event)) return ignored("ping");
    if (!PUSH.equals(event)) return ignored("event");
    if (delivery == null || !delivery.matches("[A-Za-z0-9_-]{1,100}"))
      throw error(400, "Missing or invalid X-GitHub-Delivery");

    Build already = byDelivery(projectId, delivery);
    if (already != null) {
      Log.infof(
          "Webhook %s for project %d is a duplicate of build %d", delivery, projectId, already.id);
      return accepted("duplicate", already);
    }

    WebhookPayload payload;
    try {
      payload = WebhookPayload.parse(body);
    } catch (IOException e) {
      throw error(400, "Invalid JSON");
    }
    if (payload == null) throw error(400, "Invalid JSON");
    String ref = payload.ref();
    if (ref == null || !ref.startsWith("refs/heads/")) return ignored("ref");
    String branch = ref.substring("refs/heads/".length());
    if (branch.isEmpty()
        || branch.length() > 150
        || branch.startsWith("-")
        || !Repository.isValidRefName(ref)) return ignored("ref");
    if (payload.deleted()) return ignored("deleted");
    if (!sameRepository(project.repositoryUrl, payload.repository()))
      throw error(403, "The repository does not match this project");
    if (payload.after() == null
        || !payload.after().matches(SHA)
        || payload.after().matches("0{40}")) return ignored("commit");
    if (!branchMatches(project.webhookBranches, branch)) return ignored("branch");

    Build concurrent = activeBuild(projectId, payload.after());
    if (concurrent != null) {
      Log.infof(
          "Webhook %s for project %d is already covered by build %d",
          delivery, projectId, concurrent.id);
      return accepted("duplicate_commit", concurrent);
    }

    String actor = payload.pusher() == null ? null : payload.pusher().name();
    if (actor != null && actor.length() > 255) actor = actor.substring(0, 255);
    try {
      Build build =
          runner.submit(
              projectId,
              null,
              project.repositoryUrl,
              branch,
              project.credentialRef,
              payload.after(),
              Build.Trigger.PUSH,
              delivery,
              actor);
      Log.infof(
          "Webhook %s queued build %d for project %d on %s at %s",
          delivery, build.id, projectId, branch, payload.after());
      return accepted("queued", build);
    } catch (WebApplicationException e) {
      if (e.getResponse().getStatus() == 429) throw error(503, "Build queue is full");
      throw e;
    } catch (RuntimeException e) {
      // Two deliveries raced; the unique indexes kept the first build.
      Build existing = byDelivery(projectId, delivery);
      if (existing != null) return accepted("duplicate", existing);
      Build active = activeBuild(projectId, payload.after());
      if (active != null) return accepted("duplicate_commit", active);
      throw e;
    }
  }

  /**
   * A new transaction, so a lookup still works after the insert above rolled back on a unique
   * violation.
   */
  private static Build byDelivery(Long projectId, String delivery) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            Build.<Build>find("projectId = ?1 and triggerDelivery = ?2", projectId, delivery)
                .firstResult());
  }

  /** The build that already covers this commit, if any. */
  private static Build activeBuild(Long projectId, String revision) {
    return QuarkusTransaction.requiringNew()
        .call(() ->
            Build.<Build>find(
                    "projectId = ?1 and revision = ?2 and status in ?3 order by id desc",
                    projectId,
                    revision,
                    List.of(Build.Status.QUEUED, Build.Status.RUNNING))
                .firstResult());
  }

  /** Reads the raw body, so the HMAC covers exactly the bytes GitHub signed. */
  byte[] read(java.io.InputStream input) throws IOException {
    return read(input, Math.max(1, maxBytes));
  }

  static byte[] read(java.io.InputStream input, int limit) throws IOException {
    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) != -1) {
      if (bytes.size() + read > limit) throw error(413, "The webhook body is too large");
      bytes.write(buffer, 0, read);
    }
    return bytes.toByteArray();
  }

  private static Map<String, Object> ignored(String reason) {
    return Map.of("status", "ignored", "reason", reason);
  }

  private static Map<String, Object> accepted(String status, Build build) {
    return Map.of(
        "status",
        status,
        "buildId",
        build.id,
        "branch",
        build.branch,
        "revision",
        build.revision == null ? "" : build.revision);
  }

  /** Whether the payload names the project's repository, never the other way around. */
  static boolean sameRepository(String stored, WebhookPayload.Repository repository) {
    if (stored == null || repository == null) return false;
    String expected = normalizeRepository(stored);
    if (expected.isEmpty()) return false;
    if (repository.cloneUrl() != null
        && !expected.equals(normalizeRepository(repository.cloneUrl()))) return false;
    if (repository.fullName() != null) {
      String fullName = repository.fullName().strip().toLowerCase(Locale.ROOT);
      // GitHub reports exactly "owner/repo". Compare the whole pair, so a longer stored path
      // cannot match by suffix.
      if (!fullName.matches("[^/]+/[^/]+")) return false;
      String expectedPath = expected.substring(expected.indexOf('/') + 1);
      if (!fullName.equals(expectedPath)) return false;
    }
    return repository.cloneUrl() != null || repository.fullName() != null;
  }

  /**
   * A host and port plus path, without a scheme, credentials, a trailing slash or a {@code .git}
   * suffix. A default port is left out, and a repository URL is case-insensitive on the providers
   * cisys supports.
   */
  static String normalizeRepository(String url) {
    if (url == null) return "";
    try {
      URI uri = URI.create(url.strip());
      String host = uri.getHost();
      String path = uri.getPath();
      if (host == null || path == null) return "";
      int port = uri.getPort();
      String scheme = uri.getScheme();
      boolean defaultPort =
          port == -1
              || (port == 443 && "https".equalsIgnoreCase(scheme))
              || (port == 80 && "http".equalsIgnoreCase(scheme));
      String authority = defaultPort ? host : host + ":" + port;
      path = path.replaceFirst("/+$", "").replaceFirst("(?i)\\.git$", "");
      path = path.replaceFirst("^/+", "");
      return (authority + "/" + path).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      return "";
    }
  }

  /** A comma-separated list of branch globs; blank means every branch. */
  static boolean branchMatches(String patterns, String branch) {
    if (patterns == null || patterns.isBlank()) return true;
    for (String pattern : patterns.split(",")) {
      String trimmed = pattern.strip();
      if (!trimmed.isEmpty() && glob(trimmed, branch)) return true;
    }
    return false;
  }

  private static boolean glob(String pattern, String value) {
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < pattern.length(); i++) {
      char c = pattern.charAt(i);
      if (c == '*') regex.append(".*");
      else if (c == '?') regex.append('.');
      else regex.append(Pattern.quote(String.valueOf(c)));
    }
    return Pattern.compile(regex.append('$').toString()).matcher(value).matches();
  }

  private static WebApplicationException error(int status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }
}
