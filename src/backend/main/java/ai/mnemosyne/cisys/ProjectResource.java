/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.Authenticated;
import org.eclipse.microprofile.jwt.JsonWebToken;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import jakarta.persistence.PersistenceException;

@Path("/api") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class ProjectResource {
    @Inject SecurityIdentity identity;
    private JsonWebToken token() {
        if (identity.getPrincipal() instanceof JsonWebToken jwt) return jwt;
        throw new NotAuthorizedException("Bearer");
    }
    private String subject() {
        String sub = token().getSubject();
        if (sub == null || sub.isBlank()) throw new NotAuthorizedException("Bearer");
        return sub;
    }
    private void requireCisysRole() {
        if (!(identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
    }
    public record CreateProject(String name, String repositoryUrl, boolean privateRepository) {}
    @GET @Path("/session") public Map<String,Object> session() {
        requireCisysRole();
        return Map.of("username", token().getClaim("preferred_username") == null ? subject() : token().getClaim("preferred_username"), "subject", subject(), "roles", identity.getRoles());
    }
    @GET @Path("/projects") public List<Project> list() {
        requireCisysRole();
        if (identity.hasRole("admin")) return Project.listAll();
        return Project.list("privateRepository = false or ownerSubject = ?1", subject());
    }
    @POST @Path("/projects") @Transactional public Response create(CreateProject input) {
        if (!(identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
        if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 100 || input.repositoryUrl() == null || input.repositoryUrl().length() > 2048) throw new BadRequestException("Name and repository URL are required");
        try { URI url = URI.create(input.repositoryUrl().trim()); if (!"https".equals(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null) throw new IllegalArgumentException(); } catch (IllegalArgumentException e) { throw new BadRequestException("Use an HTTPS repository URL without credentials"); }
        String name = input.name().trim(), url = input.repositoryUrl().trim();
        // The name is the project's URL path, for example /cisys/ in the UI.
        if (name.contains("/")) throw new BadRequestException("Project names cannot contain /");
        // Checked across all projects, including private projects the caller cannot see.
        if (Project.count("lower(name) = ?1", name.toLowerCase(Locale.ROOT)) > 0) throw conflict("A project with this name already exists");
        if (Project.count("repositoryUrl", url) > 0) throw conflict("A project with this repository URL already exists");
        Project p = new Project(); p.name = name; p.repositoryUrl = url; p.privateRepository = input.privateRepository(); p.ownerSubject = subject();
        try { p.persistAndFlush(); }
        catch (PersistenceException e) { throw conflict("A project with this name or repository URL already exists"); }
        return Response.status(201).entity(p).build();
    }
    /** Deletes a project and its builds; the database unlinks their logs. Admins, or the lead who owns it. */
    @DELETE @Path("/projects/{id}") @Transactional public Response delete(@PathParam("id") Long id) {
        if (!(identity.hasRole("lead") || identity.hasRole("admin"))) throw new ForbiddenException();
        Project p = Project.findById(id);
        boolean admin = identity.hasRole("admin");
        boolean owner = p != null && subject().equals(p.ownerSubject);
        // Private projects the caller cannot see answer 404, as everywhere else.
        if (p == null || (p.privateRepository && !admin && !owner)) throw new NotFoundException();
        if (!admin && !owner) throw new ForbiddenException();
        if (Build.count("projectId = ?1 and status = 'RUNNING'", id) > 0) throw conflict("A build is running. Delete the project when it finishes.");
        Build.delete("projectId", id);
        p.delete();
        return Response.noContent().build();
    }
    private static WebApplicationException conflict(String message) {
        return new WebApplicationException(Response.status(409).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build());
    }
}
