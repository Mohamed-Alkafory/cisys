/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.ext.Provider;
import java.lang.reflect.Method;

/**
 * Records refused and failed calls of annotated mutations. Success is recorded by the resource
 * itself, inside its transaction. Only 403 (DENIED), 409 and 502 (FAILURE) are recorded: 400/401
 * are validation or authentication errors, 404 must not reveal targets, and 429 is a queue limit.
 * The audit write is best-effort and never changes the response.
 */
@Provider
public class AuditFailureFilter implements ContainerResponseFilter {
  @Inject AuditService audit;
  @Inject ResourceInfo resourceInfo;

  @Override
  public void filter(ContainerRequestContext request, ContainerResponseContext response) {
    int status = response.getStatus();
    AuditOutcome outcome =
        switch (status) {
          case 403 -> AuditOutcome.DENIED;
          case 409, 502 -> AuditOutcome.FAILURE;
          default -> null;
        };
    if (outcome == null) {
      return;
    }
    Method method = resourceInfo.getResourceMethod();
    Audited audited = method == null ? null : method.getAnnotation(Audited.class);
    if (audited == null) {
      return;
    }
    audit.recordFailure(audited, outcome, request, status);
  }
}
