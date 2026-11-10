/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * The page of a single project, addressed by its name (for example /cisys/ in the UI).
 * Public projects are readable by anyone, including visitors who are not signed in.
 * Private projects are only readable by their owner and admins; everyone else gets a 404,
 * so the existence of a private project is not revealed.
 */
@Path("/api/project-pages/{name}")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class ProjectPageResource {
    @Inject SecurityIdentity identity;

    public record ProjectInfo(Long id, String name, String repositoryUrl, boolean privateRepository) {}
    /** Leaves out who requested the build and any credential reference, as the page may be anonymous. */
    public record PageBuild(Long id, String status, String revision, String branch, String image, String command,
                            Instant startedAt, Instant finishedAt, String output) {}
    public record ProjectPage(ProjectInfo project, List<PageBuild> builds) {}

    @GET
    public ProjectPage page(@PathParam("name") String name) {
        Project p = Project.<Project>find("lower(name) = ?1", name.trim().toLowerCase(Locale.ROOT)).firstResult();
        if (p == null || (p.privateRepository && !canSeePrivate(p))) throw new NotFoundException();
        List<PageBuild> builds = Build.<Build>find("projectId = ?1 order by id desc", p.id).page(0, 50).list().stream()
                .map(b -> new PageBuild(b.id, b.status, b.revision, b.branch, b.image, b.command, b.startedAt, b.finishedAt, b.output))
                .toList();
        return new ProjectPage(new ProjectInfo(p.id, p.name, p.repositoryUrl, p.privateRepository), builds);
    }

    /** Same rule as the authenticated project and build endpoints: a Cisys role, and admin or owner. */
    private boolean canSeePrivate(Project p) {
        if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken jwt) || jwt.getSubject() == null) return false;
        if (!(identity.hasRole("member") || identity.hasRole("lead") || identity.hasRole("admin"))) return false;
        return identity.hasRole("admin") || jwt.getSubject().equals(p.ownerSubject);
    }
}
