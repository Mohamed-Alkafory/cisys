/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.time.Instant;
import java.util.Map;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** The owner's controls for push-triggered builds. Admins may manage every project. */
@Path("/api/projects/{projectId}/webhook")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class ProjectWebhookResource {
  @Inject SecurityIdentity identity;
  @Context UriInfo uri;

  public record Settings(Boolean enabled, String branches) {}

  public record Secret(String secret) {}

  public record LastBuild(
      Long id, Build.Status status, String revision, String branch, Instant requestedAt) {}

  public record Webhook(
      boolean enabled,
      String url,
      String branches,
      boolean secretSet,
      @JsonInclude(JsonInclude.Include.NON_NULL) LastBuild lastBuild) {}

  private JsonWebToken token() {
    if (identity.getPrincipal() instanceof JsonWebToken jwt) return jwt;
    throw new NotAuthorizedException("Bearer");
  }

  private String subject() {
    String sub = token().getSubject();
    if (sub == null || sub.isBlank()) throw new NotAuthorizedException("Bearer");
    return sub;
  }

  /** Owners and admins; a private project the caller cannot see answers 404. */
  private Project project(Long id) {
    if (!(identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
    Project p = Project.findById(id);
    boolean admin = identity.hasRole("admin");
    boolean owner = p != null && subject().equals(p.ownerSubject);
    if (p == null || (p.privateRepository && !admin && !owner)) throw new NotFoundException();
    if (!admin && !owner) throw new ForbiddenException();
    return p;
  }

  private Webhook response(Project p) {
    Build build =
        Build.<Build>find(
                "projectId = ?1 and trigger = ?2 order by id desc", p.id, Build.Trigger.PUSH)
            .firstResult();
    LastBuild last =
        build == null
            ? null
            : new LastBuild(
                build.id, build.status, build.revision, build.branch, build.requestedAt);
    String url =
        uri.getBaseUriBuilder()
            .path("api/webhooks/git")
            .path(String.valueOf(p.id))
            .build()
            .toString();
    return new Webhook(
        p.webhookEnabled,
        url,
        p.webhookBranches == null || p.webhookBranches.isBlank() ? "*" : p.webhookBranches,
        p.webhookSecret != null && !p.webhookSecret.isBlank(),
        last);
  }

  @GET
  public Webhook get(@PathParam("projectId") Long id) {
    return response(project(id));
  }

  @GET
  @Path("/secret")
  public Secret secret(@PathParam("projectId") Long id) {
    Project p = project(id);
    if (p.webhookSecret == null || p.webhookSecret.isBlank())
      throw new NotFoundException("No webhook secret");
    return new Secret(p.webhookSecret);
  }

  @POST
  @Path("/secret")
  @Transactional
  public Secret rotate(@PathParam("projectId") Long id) {
    Project p = project(id);
    p.webhookSecret = WebhookSignature.secret();
    return new Secret(p.webhookSecret);
  }

  @PUT
  @Transactional
  @Consumes(MediaType.APPLICATION_JSON)
  public Webhook update(@PathParam("projectId") Long id, Settings input) {
    Project p = project(id);
    boolean enabled = input != null && Boolean.TRUE.equals(input.enabled());
    if (enabled && (p.webhookSecret == null || p.webhookSecret.isBlank()))
      throw badRequest("Generate a webhook secret first");
    p.webhookEnabled = enabled;
    p.webhookBranches = branches(input == null ? null : input.branches());
    return response(p);
  }

  @DELETE
  @Transactional
  public Response disable(@PathParam("projectId") Long id) {
    Project p = project(id);
    p.webhookEnabled = false;
    p.webhookSecret = null;
    p.webhookBranches = null;
    return Response.noContent().build();
  }

  /**
   * Comma-separated branch globs. A blank list means every branch; branch names and globs cannot
   * contain whitespace or a comma.
   */
  static String branches(String value) {
    if (value == null || value.isBlank()) return "*";
    String trimmed = value.strip();
    if (trimmed.length() > 1000) throw badRequest("The branch filter is too long");
    for (String pattern : trimmed.split(",", -1)) {
      String p = pattern.strip();
      if (p.isEmpty()) throw badRequest("The branch filter contains an empty pattern");
      if (p.length() > 150) throw badRequest("A branch pattern is too long");
      for (int i = 0; i < p.length(); i++)
        if (Character.isWhitespace(p.charAt(i)) || Character.isISOControl(p.charAt(i)))
          throw badRequest("Branch patterns cannot contain whitespace");
    }
    return trimmed;
  }

  private static WebApplicationException badRequest(String message) {
    return new WebApplicationException(
        Response.status(400)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }
}
