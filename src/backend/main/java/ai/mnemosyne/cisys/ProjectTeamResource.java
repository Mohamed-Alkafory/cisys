/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import ai.mnemosyne.cisys.ProjectMember.TeamRole;
import io.quarkus.logging.Log;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Project team: list, add, change and remove members. Subjects never leave the server; usernames
 * do. Every project keeps at least one LEAD: removing or demoting the last one answers 409
 * Conflict. The project's creator is protected: other Project LEADs cannot demote or remove the
 * creator; only a global ADMIN can (and is never re-promoted afterwards).
 */
@Path("/api/projects/{projectId}/team")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ProjectTeamResource {
  @Inject ProjectAccess access;
  @Inject ProjectMemberService memberService;
  @Inject UserDirectory directory;

  /** One membership entry shown to callers: the user's name and their project role. */
  public record MemberResponse(String username, String role) {}

  /** A request to add an existing Keycloak user to the project, with the role to assign. */
  public record AddMember(String username, String role) {}

  /** A request to assign a different role to a member of the project. */
  public record ChangeRole(String role) {}

  /**
   * The project's members; visible to its members and admins, 404 when missing or private, 403 for
   * anyone else.
   */
  @GET
  public List<MemberResponse> list(@PathParam("projectId") Long id) {
    Project p = access.visible(id);
    if (!access.canViewMembers(p)) {
      throw new ForbiddenException();
    }
    return ProjectMember.<ProjectMember>find("projectId = ?1 order by createdAt, id", id)
        .list()
        .stream()
        .map(m -> new MemberResponse(usernameOf(m.subject), m.role.name()))
        .toList();
  }

  /**
   * Adds an existing Keycloak user with the requested role; 400 for a missing username or role, 404
   * when the user does not exist or the project is not visible, 409 when they are already a member.
   * Requires project LEAD or global admin.
   */
  @POST
  @Transactional
  public Response add(@PathParam("projectId") Long id, AddMember input) {
    memberService.lockProject(access.manageable(id));
    if (input == null || input.username() == null || input.username().isBlank()) {
      throw badRequest("username is required");
    }
    String username = input.username().trim();
    TeamRole role = role(input.role());
    String subject = subjectOf(username);
    if (subject == null) {
      throw notFound("No user named '" + username + "' exists in Keycloak");
    }
    if (ProjectMember.count("projectId = ?1 and subject = ?2", id, subject) > 0) {
      throw conflict("'" + username + "' is already a member of this project");
    }
    ProjectMember m = new ProjectMember();
    m.projectId = id;
    m.subject = subject;
    m.role = role;
    m.createdAt = Instant.now();
    m.persist();
    return Response.status(201).entity(new MemberResponse(username, role.name())).build();
  }

  /**
   * Sets the member's role; 400 for a missing or invalid role, 404 when the user does not exist or
   * is not a member of the project, 409 when the change would leave the project without a LEAD; 403
   * when tried by a non-admin on the creator's membership. Requires project LEAD or global admin.
   */
  @PUT
  @Path("/{username}")
  @Transactional
  public MemberResponse change(
      @PathParam("projectId") Long id, @PathParam("username") String username, ChangeRole input) {
    Project project = memberService.lockProject(access.manageable(id));
    TeamRole role = role(input == null ? null : input.role());
    ProjectMember m = existing(id, username.trim());
    memberService.protectCreator(project, m, role);
    if (m.role == TeamRole.LEAD && role != TeamRole.LEAD) {
      memberService.requireAnotherLead(id);
    }
    m.role = role;
    return new MemberResponse(username.trim(), role.name());
  }

  /**
   * Removes the member; 404 when the user does not exist or is not a member of the project, 409
   * when the removal would leave the project without a LEAD, 403 when tried by a non-admin on the
   * creator's membership; requires project LEAD or global admin.
   */
  @DELETE
  @Path("/{username}")
  @Transactional
  public Response remove(@PathParam("projectId") Long id, @PathParam("username") String username) {
    Project project = memberService.lockProject(access.manageable(id));
    ProjectMember m = existing(id, username.trim());
    memberService.protectCreator(project, m, null);
    if (m.role == TeamRole.LEAD) {
      memberService.requireAnotherLead(id);
    }
    m.delete();
    return Response.noContent().build();
  }

  private static TeamRole role(String value) {
    if (value == null || value.isBlank()) {
      throw badRequest("role is required (MEMBER or LEAD)");
    }
    try {
      return TeamRole.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw badRequest("role must be MEMBER or LEAD");
    }
  }

  private ProjectMember existing(Long id, String username) {
    String subject = subjectOf(username);
    if (subject == null) {
      throw notFound("'" + username + "' is not a member of this project");
    }
    ProjectMember m =
        ProjectMember.<ProjectMember>find("projectId = ?1 and subject = ?2", id, subject)
            .firstResult();
    if (m == null) {
      throw notFound("'" + username + "' is not a member of this project");
    }
    return m;
  }

  private String subjectOf(String username) {
    try {
      for (UserDirectory.User u : directory.search(username, 0, 100)) {
        if (username.equalsIgnoreCase(u.username())) {
          return u.id();
        }
      }
      return null;
    } catch (UserDirectory.UnavailableException e) {
      throw badGateway(e);
    }
  }

  private String usernameOf(String subject) {
    return directory.find(subject).map(UserDirectory.User::username).orElse("unknown");
  }

  /**
   * The details name Keycloak's admin endpoints, so they are logged and the client gets a generic
   * message.
   */
  private WebApplicationException badGateway(UserDirectory.UnavailableException e) {
    Log.warnf("User lookup failed: %s", e.getMessage());
    return problem(502, "The user directory is unavailable; try again later");
  }

  private static WebApplicationException notFound(String message) {
    return problem(404, message);
  }

  private static WebApplicationException badRequest(String message) {
    return problem(400, message);
  }

  private static WebApplicationException conflict(String message) {
    return problem(409, message);
  }

  /**
   * Entity-carrying JSON error; the framework default for message-only exceptions drops the body.
   */
  private static WebApplicationException problem(int status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }
}
