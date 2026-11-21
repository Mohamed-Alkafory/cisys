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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The hosts build steps may reach, for admins. */
@Path("/api/network-hosts")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NetworkHostResource {
  static final int MAX_NAME = 100;

  @Inject SecurityIdentity identity;
  @Inject AuditService audit;

  /** A host an admin allows. */
  public record Input(String name, String host) {}

  /** An allowed host. */
  public record View(Long id, String name, String host, String createdBy, Instant createdAt) {}

  /** The allowed hosts. */
  @GET
  public List<View> list() {
    requireAdmin();
    return views();
  }

  /** Allows a host; 409 when it is already allowed. */
  @POST
  @Transactional
  @Audited(
      action = AuditAction.NETWORK_HOST_CREATE,
      target = AuditTargetType.NETWORK_HOST,
      summary = "Allow a network host")
  public Response create(Input input) {
    requireAdmin();
    String name = name(input);
    String host = host(input);
    if (NetworkHost.count("host", host) > 0) {
      throw error(409, host + " is already allowed");
    }
    if (NetworkHost.count() >= NetworkAllowlist.MAX_ENTRIES) {
      throw tooMany();
    }
    NetworkHost created = add(name, host);
    audit.success(
        AuditAction.NETWORK_HOST_CREATE,
        AuditTargetType.NETWORK_HOST,
        created.id,
        created.host,
        (Long) null,
        (String) null,
        "Allowed network host " + created.host,
        AuditDetails.of("name", created.name, "host", created.host));
    return Response.status(201).entity(view(created)).build();
  }

  /** Changes an allowed host. */
  @PUT
  @Path("/{id}")
  @Transactional
  @Audited(
      action = AuditAction.NETWORK_HOST_UPDATE,
      target = AuditTargetType.NETWORK_HOST,
      summary = "Change a network host")
  public View update(@PathParam("id") Long id, Input input) {
    requireAdmin();
    NetworkHost h = NetworkHost.findById(id);
    if (h == null) {
      throw new NotFoundException();
    }
    String previousName = h.name;
    String previousHost = h.host;
    String name = name(input);
    String host = host(input);
    if (NetworkHost.count("host = ?1 and id <> ?2", host, id) > 0) {
      throw error(409, host + " is already allowed");
    }
    h.name = name;
    h.host = host;
    java.util.List<String> changed = new java.util.ArrayList<>();
    if (!java.util.Objects.equals(previousName, h.name)) {
      changed.add("name");
    }
    if (!java.util.Objects.equals(previousHost, h.host)) {
      changed.add("host");
    }
    audit.success(
        AuditAction.NETWORK_HOST_UPDATE,
        AuditTargetType.NETWORK_HOST,
        h.id,
        h.host,
        (Long) null,
        (String) null,
        "Changed network host " + h.host,
        AuditDetails.of("changed", changed, "name", h.name, "host", h.host));
    return view(h);
  }

  /** Removes an allowed host. */
  @DELETE
  @Path("/{id}")
  @Transactional
  @Audited(
      action = AuditAction.NETWORK_HOST_DELETE,
      target = AuditTargetType.NETWORK_HOST,
      summary = "Remove a network host")
  public Response delete(@PathParam("id") Long id) {
    requireAdmin();
    NetworkHost h = NetworkHost.findById(id);
    if (h == null) {
      throw new NotFoundException();
    }
    String name = h.name;
    String host = h.host;
    h.delete();
    audit.success(
        AuditAction.NETWORK_HOST_DELETE,
        AuditTargetType.NETWORK_HOST,
        id,
        host,
        (Long) null,
        (String) null,
        "Removed network host " + host,
        AuditDetails.of("name", name, "host", host));
    return Response.noContent().build();
  }

  /** The {@link NetworkTemplate}s. */
  @GET
  @Path("/templates")
  public List<NetworkTemplate> templates() {
    requireAdmin();
    return NetworkTemplate.ALL;
  }

  /** Adds the template's hosts that are not allowed yet, named after the template. */
  @POST
  @Path("/templates/{id}")
  @Consumes(MediaType.WILDCARD)
  @Transactional
  @Audited(
      action = AuditAction.NETWORK_TEMPLATE_ADD,
      target = AuditTargetType.NETWORK_TEMPLATE,
      summary = "Add a network template")
  public List<View> addTemplate(@PathParam("id") String id) {
    requireAdmin();
    NetworkTemplate template = NetworkTemplate.find(id).orElseThrow(NotFoundException::new);
    List<String> missing =
        template.hosts().stream().filter(h -> NetworkHost.count("host", h) == 0).toList();
    if (NetworkHost.count() + missing.size() > NetworkAllowlist.MAX_ENTRIES) {
      throw tooMany();
    }
    for (String host : missing) {
      add(template.name(), host);
    }
    audit.success(
        AuditAction.NETWORK_TEMPLATE_ADD,
        AuditTargetType.NETWORK_TEMPLATE,
        template.id(),
        template.name(),
        (Long) null,
        (String) null,
        "Added the network template " + template.name(),
        AuditDetails.of("template", template.name(), "added", missing));
    return views();
  }

  private NetworkHost add(String name, String host) {
    NetworkHost h = new NetworkHost();
    h.name = name;
    h.host = host;
    h.createdBy = identity.getPrincipal().getName();
    h.createdAt = Instant.now();
    h.persist();
    return h;
  }

  private static String name(Input input) {
    String name = input == null || input.name() == null ? "" : input.name().strip();
    if (name.isEmpty() || name.length() > MAX_NAME) {
      throw error(400, "A name of up to " + MAX_NAME + " characters is required");
    }
    return name;
  }

  private static String host(Input input) {
    try {
      return NetworkAllowlist.entry(input == null ? null : input.host());
    } catch (IllegalArgumentException e) {
      throw error(400, e.getMessage());
    }
  }

  private static List<View> views() {
    return NetworkHost.<NetworkHost>list("order by name, host").stream()
        .map(NetworkHostResource::view)
        .toList();
  }

  private static View view(NetworkHost h) {
    return new View(h.id, h.name, h.host, h.createdBy, h.createdAt);
  }

  private static WebApplicationException tooMany() {
    return error(400, "At most " + NetworkAllowlist.MAX_ENTRIES + " hosts are allowed");
  }

  private static WebApplicationException error(int status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }

  private void requireAdmin() {
    if (!identity.hasRole("admin")) {
      throw new ForbiddenException();
    }
  }
}
