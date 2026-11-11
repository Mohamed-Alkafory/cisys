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
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/** Admin settings. */
@Path("/api/settings")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsResource {
    static final int MAX_DAYS = 3650, MAX_BUILDS = 10000;

    @Inject SecurityIdentity identity;

    /** 0 turns a limit off. */
    public record Retention(Integer maxAgeDays, Integer maxBuilds) {}

    @GET @Path("/retention") public Retention retention() {
        requireAdmin();
        Settings s = Settings.current();
        return new Retention(s.retentionDays, s.retentionMaxBuilds);
    }

    @PUT @Path("/retention") @Transactional public Retention updateRetention(Retention input) {
        requireAdmin();
        if (input == null || input.maxAgeDays() == null || input.maxAgeDays() < 0 || input.maxAgeDays() > MAX_DAYS)
            throw new BadRequestException("Days must be between 0 and " + MAX_DAYS);
        if (input.maxBuilds() == null || input.maxBuilds() < 0 || input.maxBuilds() > MAX_BUILDS)
            throw new BadRequestException("Builds must be between 0 and " + MAX_BUILDS);
        Settings s = Settings.findById(1L);
        if (s == null) { s = new Settings(); s.persist(); }
        s.retentionDays = input.maxAgeDays();
        s.retentionMaxBuilds = input.maxBuilds();
        return new Retention(s.retentionDays, s.retentionMaxBuilds);
    }

    private void requireAdmin() {
        if (!identity.hasRole("admin")) throw new ForbiddenException();
    }
}
