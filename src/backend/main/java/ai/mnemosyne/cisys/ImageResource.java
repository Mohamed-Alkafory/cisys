/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** The images builds may use, and the images on the build host. */
@Path("/api/images")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ImageResource {
  @Inject SecurityIdentity identity;
  @Inject BuildRunner runner;
  @Inject AuditService audit;

  /** The images allowed for builds. */
  public record Selection(List<String> images) {}

  /** An image to pull to the build host. */
  public record Pull(String name) {}

  /** An image on the build host, and whether builds may use it. */
  public record LocalImage(String name, String id, String size, String created, boolean allowed) {}

  /** The images allowed for builds, for the build form. */
  @GET
  public List<String> allowed() {
    if (!(identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin"))) {
      throw new ForbiddenException();
    }
    return names();
  }

  /** The images present in the local container runtime, marked with whether each is allowed. */
  @GET
  @Path("/local")
  public List<LocalImage> local() {
    requireAdmin();
    Set<String> allowed = new HashSet<>(names());
    List<BuildRunner.LocalImage> local;
    try {
      local = runner.images();
    } catch (Exception e) {
      throw failure(unreachable());
    }
    return local.stream()
        .map(i ->
            new LocalImage(i.name(), i.id(), i.size(), i.created(), allowed.contains(i.name())))
        .toList();
  }

  /** Pulls an image into the local container runtime. */
  @POST
  @Path("/local")
  @Audited(action = AuditAction.IMAGE_PULL, target = AuditTargetType.IMAGE, summary = "Pull image")
  public List<LocalImage> pull(Pull input) {
    requireAdmin();
    String name = validName(input == null ? null : input.name());
    Optional<String> failed;
    try {
      failed = runner.pull(name);
    } catch (Exception e) {
      throw failure(unreachable());
    }
    if (failed.isPresent()) {
      throw failure("Unable to pull " + name + ": " + failed.get());
    }
    audit.bestEffortSuccess(
        AuditAction.IMAGE_PULL,
        AuditTargetType.IMAGE,
        name,
        name,
        (Long) null,
        (String) null,
        "Pulled image " + name,
        AuditDetails.of());
    return local();
  }

  /** Removes an image from the local container runtime, and from the allowed images. */
  @DELETE
  @Path("/local")
  @Audited(
      action = AuditAction.IMAGE_REMOVE,
      target = AuditTargetType.IMAGE,
      summary = "Remove image")
  public List<LocalImage> remove(@QueryParam("name") String name) {
    requireAdmin();
    validName(name);
    Optional<String> failed;
    try {
      failed = runner.remove(name);
    } catch (Exception e) {
      throw failure(unreachable());
    }
    if (failed.isPresent()) {
      throw failure("Unable to remove " + name + ": " + failed.get());
    }
    boolean allowed =
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
            .call(() -> AllowedImage.deleteById(name));
    audit.bestEffortSuccess(
        AuditAction.IMAGE_REMOVE,
        AuditTargetType.IMAGE,
        name,
        name,
        (Long) null,
        (String) null,
        "Removed image " + name,
        AuditDetails.of("removedFromAllowlist", allowed));
    return local();
  }

  /** Replaces the set of allowed images. */
  @PUT
  @Transactional
  @Audited(
      action = AuditAction.IMAGE_ALLOWLIST_UPDATE,
      target = AuditTargetType.IMAGE,
      summary = "Update the allowed images")
  public List<String> update(Selection input) {
    requireAdmin();
    if (input == null || input.images() == null || input.images().size() > 500) {
      throw new BadRequestException("A list of images is required");
    }
    Set<String> before = new TreeSet<>(names());
    Set<String> wanted = new TreeSet<>();
    for (String name : input.images()) {
      wanted.add(validName(name));
    }
    AllowedImage.delete("name not in ?1", wanted.isEmpty() ? Set.of("") : wanted);
    for (String name : wanted) {
      if (AllowedImage.findById(name) == null) {
        AllowedImage image = new AllowedImage();
        image.name = name;
        image.persist();
      }
    }
    Set<String> after = new TreeSet<>(names());
    Set<String> added = new TreeSet<>(wanted);
    added.removeAll(before);
    Set<String> removed = new TreeSet<>(before);
    removed.removeAll(after);
    audit.success(
        AuditAction.IMAGE_ALLOWLIST_UPDATE,
        AuditTargetType.IMAGE,
        "allowlist",
        "allowed images",
        (Long) null,
        (String) null,
        "Updated the allowed images",
        AuditDetails.of("added", added, "removed", removed, "total", after.size()));
    return names();
  }

  private String unreachable() {
    return "Unable to run '"
        + runner.runtime()
        + "' on the backend host. Install podman or docker.";
  }

  private void requireAdmin() {
    if (!identity.hasRole("admin")) {
      throw new ForbiddenException();
    }
  }

  private static String validName(String name) {
    if (name == null || !name.matches(AllowedImage.PATTERN)) {
      throw new WebApplicationException(
          Response.status(400)
              .type(MediaType.APPLICATION_JSON)
              .entity(Map.of("error", "Invalid image name"))
              .build());
    }
    return name;
  }

  private static WebApplicationException failure(String message) {
    return new WebApplicationException(
        Response.status(502)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }

  private static List<String> names() {
    return AllowedImage.<AllowedImage>listAll().stream().map(i -> i.name).sorted().toList();
  }
}
