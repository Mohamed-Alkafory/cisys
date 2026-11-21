/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/** Captures the method, path and caller-supplied request id for the audit events of a request. */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuditRequestFilter implements ContainerRequestFilter {
  @Inject AuditRequest auditRequest;

  @Override
  public void filter(ContainerRequestContext context) {
    auditRequest.requestId = context.getHeaderString(AuditContext.REQUEST_ID_HEADER);
    auditRequest.method = context.getMethod();
    auditRequest.path = context.getUriInfo().getRequestUri().getPath();
  }
}
