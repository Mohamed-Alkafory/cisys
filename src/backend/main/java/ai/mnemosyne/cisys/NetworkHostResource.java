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

  public record Input(String name, String host) {}

  public record View(Long id, String name, String host, String createdBy, Instant createdAt) {}

  @GET
  public List<View> list() {
    requireAdmin();
    return views();
  }

  @POST
  @Transactional
  public Response create(Input input) {
    requireAdmin();
    String name = name(input);
    String host = host(input);
    if (NetworkHost.count("host", host) > 0) throw error(409, host + " is already allowed");
    if (NetworkHost.count() >= NetworkAllowlist.MAX_ENTRIES) throw tooMany();
    return Response.status(201).entity(view(add(name, host))).build();
  }

  @PUT
  @Path("/{id}")
  @Transactional
  public View update(@PathParam("id") Long id, Input input) {
    requireAdmin();
    NetworkHost h = NetworkHost.findById(id);
    if (h == null) throw new NotFoundException();
    String name = name(input);
    String host = host(input);
    if (NetworkHost.count("host = ?1 and id <> ?2", host, id) > 0)
      throw error(409, host + " is already allowed");
    h.name = name;
    h.host = host;
    return view(h);
  }

  @DELETE
  @Path("/{id}")
  @Transactional
  public Response delete(@PathParam("id") Long id) {
    requireAdmin();
    if (!NetworkHost.deleteById(id)) throw new NotFoundException();
    return Response.noContent().build();
  }

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
  public List<View> addTemplate(@PathParam("id") String id) {
    requireAdmin();
    NetworkTemplate template = NetworkTemplate.find(id).orElseThrow(NotFoundException::new);
    List<String> missing =
        template.hosts().stream().filter(h -> NetworkHost.count("host", h) == 0).toList();
    if (NetworkHost.count() + missing.size() > NetworkAllowlist.MAX_ENTRIES) throw tooMany();
    for (String host : missing) add(template.name(), host);
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
    if (name.isEmpty() || name.length() > MAX_NAME)
      throw error(400, "A name of up to " + MAX_NAME + " characters is required");
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
    if (!identity.hasRole("admin")) throw new ForbiddenException();
  }
}
