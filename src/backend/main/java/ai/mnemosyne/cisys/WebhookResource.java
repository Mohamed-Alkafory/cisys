/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * GitHub's push webhook. It is intentionally anonymous: GitHub cannot sign in, so the HMAC over the
 * raw body is the authentication.
 */
@Path("/api/webhooks/git")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WebhookResource {
  @Inject WebhookService webhooks;

  @POST
  @Path("/{projectId}")
  public Response receive(
      @PathParam("projectId") Long projectId,
      @HeaderParam("X-GitHub-Event") String event,
      @HeaderParam("X-GitHub-Delivery") String delivery,
      @HeaderParam("X-Hub-Signature-256") String signature,
      InputStream body) {
    byte[] bytes;
    try {
      bytes = WebhookService.read(body);
    } catch (IOException e) {
      throw new WebApplicationException(
          Response.status(400)
              .type(MediaType.APPLICATION_JSON)
              .entity(Map.of("error", "The webhook body could not be read"))
              .build());
    }
    Map<String, Object> result = webhooks.receive(projectId, event, delivery, signature, bytes);
    return Response.status(Response.Status.ACCEPTED).entity(result).build();
  }
}
