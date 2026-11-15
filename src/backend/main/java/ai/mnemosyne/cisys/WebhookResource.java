/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.annotation.PostConstruct;
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
import java.util.concurrent.Semaphore;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * GitHub's push webhook. It is intentionally anonymous: GitHub cannot sign in, so the HMAC over the
 * raw body is the authentication. Webhook work is bounded by a global concurrency limit, and
 * failures are counted per project only after authentication fails, so a correctly signed delivery
 * is never blocked by another request's failures. Webhook traffic never spends the public visitor
 * rate limit.
 */
@Path("/api/webhooks/git")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WebhookResource {
  @Inject WebhookService webhooks;
  @Inject WebhookFailureLimiter failures;

  @ConfigProperty(name = "cisys.webhook.max-concurrent", defaultValue = "64")
  int maxConcurrent;

  private Semaphore slots;

  @PostConstruct
  void init() {
    slots = new Semaphore(Math.max(1, maxConcurrent));
  }

  @POST
  @Path("/{projectId}")
  public Response receive(
      @PathParam("projectId") Long projectId,
      @HeaderParam("X-GitHub-Event") String event,
      @HeaderParam("X-GitHub-Delivery") String delivery,
      @HeaderParam("X-Hub-Signature-256") String signature,
      InputStream body) {
    if (!slots.tryAcquire()) {
      return Response.status(429)
          .type(MediaType.APPLICATION_JSON)
          .header("Retry-After", 1)
          .entity(Map.of("error", "Too many webhook requests. Try again later."))
          .build();
    }
    try {
      String key = String.valueOf(projectId);
      // A header that cannot be a signature is rejected before the body is read, so an
      // unauthenticated flood cannot make the backend allocate and hash a megabyte per request.
      if (!WebhookSignature.wellFormed(signature))
        throw failed(key, error(401, "Invalid signature"));
      byte[] bytes;
      try {
        bytes = webhooks.read(body);
      } catch (IOException e) {
        throw error(400, "The webhook body could not be read");
      } catch (WebApplicationException e) {
        if (e.getResponse().getStatus() == 413) throw failed(key, e);
        throw e;
      }
      try {
        Map<String, Object> result = webhooks.receive(projectId, event, delivery, signature, bytes);
        return Response.status(Response.Status.ACCEPTED).entity(result).build();
      } catch (WebApplicationException e) {
        if (e.getResponse().getStatus() == 401) throw failed(key, e);
        throw e;
      }
    } finally {
      slots.release();
    }
  }

  /**
   * Records a failed authentication for the project. A valid delivery never consults this budget,
   * so one source cannot lock a project's real deliveries out.
   */
  private WebApplicationException failed(String key, WebApplicationException original) {
    long retryAfter = failures.recordFailure(key);
    if (retryAfter <= 0) return original;
    return new WebApplicationException(
        Response.status(429)
            .type(MediaType.APPLICATION_JSON)
            .header("Retry-After", retryAfter)
            .entity(Map.of("error", "Too many failed webhook attempts. Try again later."))
            .build());
  }

  private static WebApplicationException error(int status, String message) {
    return new WebApplicationException(
        Response.status(status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }
}
