/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Branches of a public project, for its public page. Private projects are reported as not found,
 * just like the rest of the public page, so anonymous callers learn nothing about them.
 */
@Path("/api/project-pages/{name}/branches")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class PublicBranchResource {
    @Inject GitRepositoryService repositories;

    public record Branches(List<String> branches, String reason) {}

    @GET
    public Response list(@PathParam("name") String name) {
        Project project = Project.<Project>find("lower(name) = ?1", name.trim().toLowerCase(Locale.ROOT)).firstResult();
        if (project == null || project.privateRepository) throw new NotFoundException();
        try {
            return Response.ok(new Branches(repositories.listBranches(project), null)).build();
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        } catch (GitRepositoryService.BranchLookupException e) {
            return error(502, e.getMessage());
        }
    }

    private static Response error(int status, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).build();
    }
}
