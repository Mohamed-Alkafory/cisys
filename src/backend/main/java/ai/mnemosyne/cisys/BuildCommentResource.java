/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds, edits and deletes comments on a build. Project members and admins may comment; only the
 * author, while still a member, may edit. The author, project LEADs and admins may delete. Comments
 * are read through {@link ProjectPageResource}.
 */
@Path("/api/projects/{projectId}/builds/{buildId}/comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class BuildCommentResource {
  @Inject ProjectAccess access;
  @Inject UserDirectory directory;

  /** The text of a comment. */
  public record Input(String body) {}

  /**
   * A comment, with its author's username; {@code mine} when the caller wrote it, {@code canDelete}
   * when the caller may delete it.
   */
  public record View(
      Long id,
      String author,
      String body,
      Instant createdAt,
      Instant updatedAt,
      boolean mine,
      boolean canDelete) {}

  /** Adds a comment to the build. */
  @POST
  @Transactional
  public Response create(
      @PathParam("projectId") Long projectId, @PathParam("buildId") Long buildId, Input input) {
    Project p = access.buildable(projectId);
    build(projectId, buildId);
    BuildComment c = new BuildComment();
    c.buildId = buildId;
    c.authorSubject = access.subject();
    c.body = body(input);
    c.createdAt = Instant.now();
    c.persist();
    return Response.status(201)
        .entity(views(List.of(c), c.authorSubject, true, moderates(p), directory).get(0))
        .build();
  }

  /** Changes the text of the caller's own comment while they are still a project member. */
  @PUT
  @Path("/{commentId}")
  @Transactional
  public View update(
      @PathParam("projectId") Long projectId,
      @PathParam("buildId") Long buildId,
      @PathParam("commentId") Long commentId,
      Input input) {
    Project p = access.buildable(projectId);
    BuildComment c = comment(projectId, buildId, commentId);
    String subject = access.subject();
    if (!subject.equals(c.authorSubject)) {
      throw new ForbiddenException();
    }
    c.body = body(input);
    c.updatedAt = Instant.now();
    return views(List.of(c), subject, true, moderates(p), directory).get(0);
  }

  /** Deletes a comment: the author while still a member, project LEADs and admins. */
  @DELETE
  @Path("/{commentId}")
  @Transactional
  public Response delete(
      @PathParam("projectId") Long projectId,
      @PathParam("buildId") Long buildId,
      @PathParam("commentId") Long commentId) {
    Project p = access.visible(projectId);
    BuildComment c = comment(projectId, buildId, commentId);
    boolean author = access.subject().equals(c.authorSubject) && access.canComment(p);
    if (!author && !moderates(p)) {
      throw new ForbiddenException();
    }
    c.delete();
    return Response.noContent().build();
  }

  /**
   * The comments as {@code caller} sees them; authors are looked up once each. {@code member} is
   * whether the caller may comment, {@code moderator} whether they may delete anyone's comment.
   */
  static List<View> views(
      List<BuildComment> comments,
      String caller,
      boolean member,
      boolean moderator,
      UserDirectory directory) {
    Map<String, String> names = new HashMap<>();
    return comments.stream()
        .map(c -> {
          boolean mine = c.authorSubject.equals(caller);
          return new View(
              c.id,
              names.computeIfAbsent(c.authorSubject, s -> username(directory, s)),
              c.body,
              c.createdAt,
              c.updatedAt,
              mine,
              moderator || (mine && member));
        })
        .toList();
  }

  private boolean moderates(Project p) {
    return access.canManage(p, ProjectMember.TeamRole.LEAD);
  }

  private static BuildComment comment(Long projectId, Long buildId, Long commentId) {
    build(projectId, buildId);
    BuildComment c = BuildComment.findById(commentId);
    if (c == null || !buildId.equals(c.buildId)) {
      throw new NotFoundException();
    }
    return c;
  }

  /** An unreachable directory leaves the comments readable. */
  private static String username(UserDirectory directory, String subject) {
    try {
      return directory.find(subject).map(UserDirectory.User::username).orElse("unknown");
    } catch (UserDirectory.UnavailableException e) {
      return "unknown";
    }
  }

  private static void build(Long projectId, Long buildId) {
    Build b = Build.findById(buildId);
    if (b == null || !projectId.equals(b.projectId)) {
      throw new NotFoundException();
    }
  }

  private static String body(Input input) {
    String body = input == null || input.body() == null ? "" : input.body().strip();
    if (body.isEmpty() || body.length() > BuildComment.MAX_BODY) {
      throw new WebApplicationException(
          Response.status(400)
              .type(MediaType.APPLICATION_JSON)
              .entity(
                  Map.of(
                      "error",
                      "A comment of up to " + BuildComment.MAX_BODY + " characters is required"))
              .build());
    }
    return body;
  }
}
