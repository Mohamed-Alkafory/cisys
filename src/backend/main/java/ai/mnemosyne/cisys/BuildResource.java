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
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;

/** Lists, starts, cancels and pins a project's builds. */
@Path("/api/projects/{projectId}/builds")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class BuildResource {
  @Inject SecurityIdentity identity;
  @Inject BuildRunner runner;
  @Inject BuildLogs logs;
  @Inject RepositoryUrlValidator repositoryUrls;
  @Inject ProjectAccess access;

  /** The image and the commands come from the branch's {@value Workflow#PATH}. */
  public record RunRequest(String branch, String credentialRef) {}

  /**
   * Who requested the build and the credential reference are returned to admins only; the workflow
   * file only for a single build.
   */
  public record BuildResponse(
      Long id,
      Long number,
      Long projectId,
      @JsonInclude(JsonInclude.Include.NON_NULL) String requestedBy,
      Build.Status status,
      String revision,
      Instant startedAt,
      Instant finishedAt,
      String branch,
      String image,
      @JsonInclude(JsonInclude.Include.NON_NULL) String credentialRef,
      String imageDigest,
      Integer exitCode,
      boolean logTruncated,
      boolean pinned,
      Build.Trigger trigger,
      @JsonInclude(JsonInclude.Include.NON_NULL) String triggerActor,
      @JsonInclude(JsonInclude.Include.NON_NULL) String pullRequestProvider,
      @JsonInclude(JsonInclude.Include.NON_NULL) Integer pullRequest,
      @JsonInclude(JsonInclude.Include.NON_NULL) String workflow) {}

  private BuildResponse response(Build build) {
    return response(build, false);
  }

  private BuildResponse response(Build build, boolean withWorkflow) {
    boolean admin = identity.hasRole("admin");
    return new BuildResponse(
        build.id,
        build.number,
        build.projectId,
        admin ? build.requestedBy : null,
        build.status,
        build.revision,
        build.startedAt,
        build.finishedAt,
        build.branch,
        build.image,
        admin ? build.credentialRef : null,
        build.imageDigest,
        build.exitCode,
        build.logTruncated,
        build.pinned,
        build.trigger == null ? Build.Trigger.MANUAL : build.trigger,
        build.triggerActor,
        build.pullRequestProvider,
        build.pullRequest,
        withWorkflow ? build.workflow : null);
  }

  /** The project's 50 newest builds. */
  @GET
  public List<BuildResponse> list(@PathParam("projectId") Long id) {
    access.visible(id);
    return Build.<Build>find("projectId = ?1 order by number desc", id).page(0, 50).list().stream()
        .map(this::response)
        .toList();
  }

  /** One build of the project. */
  @GET
  @Path("/{buildId}")
  public BuildResponse detail(@PathParam("projectId") Long id, @PathParam("buildId") Long buildId) {
    access.visible(id);
    Build b = Build.findById(buildId);
    if (b == null || !id.equals(b.projectId)) {
      throw new NotFoundException();
    }
    return response(b, true);
  }

  /**
   * A running build returns what is stored so far. Authenticated callers get the whole log by
   * default; {@code offset}, {@code limit} and {@code tail} allow partial reads without a cap.
   */
  @GET
  @Path("/{buildId}/log")
  @Produces("text/plain; charset=UTF-8")
  public Response log(
      @PathParam("projectId") Long id,
      @PathParam("buildId") Long buildId,
      @QueryParam("offset") Long offset,
      @QueryParam("limit") Long limit,
      @QueryParam("tail") Long tail) {
    detail(id, buildId);
    long size = logs.size(buildId);
    BuildLogs.Range range;
    try {
      range = BuildLogs.range(size, offset, limit, tail, -1);
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
    return LogResponses.stream(logs, buildId, size, range);
  }

  /** Pins a build so retention cleanup keeps it. */
  @PUT
  @Path("/{buildId}/pin")
  @Transactional
  public BuildResponse pin(@PathParam("projectId") Long id, @PathParam("buildId") Long buildId) {
    return setPinned(id, buildId, true);
  }

  /** Unpins a build so retention cleanup may remove it. */
  @DELETE
  @Path("/{buildId}/pin")
  @Transactional
  public BuildResponse unpin(@PathParam("projectId") Long id, @PathParam("buildId") Long buildId) {
    return setPinned(id, buildId, false);
  }

  /** Project LEADs and global admins may pin. */
  private BuildResponse setPinned(Long id, Long buildId, boolean pinned) {
    access.editable(id);
    Build b = Build.findById(buildId);
    if (b == null || !id.equals(b.projectId)) {
      throw new NotFoundException();
    }
    b.pinned = pinned;
    return response(b, true);
  }

  /** Global admins, project LEADs, or a requester with a global lead role may cancel. */
  @POST
  @Path("/{buildId}/cancel")
  public BuildResponse cancel(@PathParam("projectId") Long id, @PathParam("buildId") Long buildId) {
    Project p = access.visible(id);
    Build b = Build.findById(buildId);
    if (b == null || !id.equals(b.projectId)) {
      throw new NotFoundException();
    }
    if (!access.mayCancel(p, b)) {
      throw new ForbiddenException();
    }
    if (!runner.cancel(buildId)) {
      throw new WebApplicationException(
          jakarta.ws.rs.core.Response.status(409)
              .type(MediaType.APPLICATION_JSON)
              .entity(java.util.Map.of("error", "The build has already finished."))
              .build());
    }
    return response(
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> Build.<Build>findById(buildId)),
        true);
  }

  /** Queues a build of a branch. Project members (any role) and global admins. */
  @POST
  public BuildResponse start(@PathParam("projectId") Long id, RunRequest input) {
    Project p = access.buildable(id);
    if (input == null
        || input.branch() == null
        || !org.eclipse.jgit.lib.Repository.isValidRefName("refs/heads/" + input.branch())
        || input.branch().startsWith("-")
        || input.branch().length() > 150) {
      throw new BadRequestException("Invalid branch");
    }
    if (input.credentialRef() != null && !identity.hasRole("admin")) {
      throw new ForbiddenException();
    }
    if (input.credentialRef() != null
        && !input.credentialRef().matches("CISYS_GIT_[A-Z0-9_]{1,80}")) {
      throw new BadRequestException("Invalid credential reference");
    }
    try {
      repositoryUrls.validate(RepositoryUrls.cloneUrl(p.repositoryUrl));
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(e.getMessage());
    }
    RunRequest request =
        input.credentialRef() == null && p.privateRepository
            ? new RunRequest(input.branch(), p.credentialRef)
            : input;
    return response(runner.submit(id, access.subject(), p.repositoryUrl, request));
  }
}
