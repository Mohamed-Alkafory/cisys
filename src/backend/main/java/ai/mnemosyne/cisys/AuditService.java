/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.Instant;
import java.util.Map;

/**
 * Writes audit events.
 *
 * <p>Database-only mutations call {@link #success} inside their own transaction, so the event and
 * the change commit or roll back together. Successful operations whose real effect is outside the
 * database (Keycloak, the container runtime, the queue runner) call {@link #bestEffortSuccess}
 * after the effect succeeds: an audit failure is logged but does not turn a successful operation
 * into an error, which leaves a documented gap between those systems and the audit log.
 *
 * <p>{@link #recordFailure} is used by {@link AuditFailureFilter}; it never changes the response.
 */
@ApplicationScoped
public class AuditService {
  @Inject AuditContext context;

  /** Records a success in the caller's transaction. */
  public AuditEvent success(
      AuditAction action,
      AuditTargetType targetType,
      Object targetId,
      String targetName,
      Project project,
      String summary,
      Map<String, Object> details) {
    return write(
        action,
        AuditOutcome.SUCCESS,
        targetType,
        targetId,
        targetName,
        project == null ? null : project.id,
        project == null ? null : project.name,
        summary,
        details);
  }

  /** Records a success with an explicit project snapshot, for detached or deleted projects. */
  public AuditEvent success(
      AuditAction action,
      AuditTargetType targetType,
      Object targetId,
      String targetName,
      Long projectId,
      String projectName,
      String summary,
      Map<String, Object> details) {
    return write(
        action,
        AuditOutcome.SUCCESS,
        targetType,
        targetId,
        targetName,
        projectId,
        projectName,
        summary,
        details);
  }

  /**
   * Records a success after an external effect in its own transaction. A failure is logged and
   * swallowed so the successful response is preserved.
   */
  public void bestEffortSuccess(
      AuditAction action,
      AuditTargetType targetType,
      Object targetId,
      String targetName,
      Project project,
      String summary,
      Map<String, Object> details) {
    bestEffort(() -> success(action, targetType, targetId, targetName, project, summary, details));
  }

  /** As {@link #bestEffortSuccess}, with an explicit project snapshot. */
  public void bestEffortSuccess(
      AuditAction action,
      AuditTargetType targetType,
      Object targetId,
      String targetName,
      Long projectId,
      String projectName,
      String summary,
      Map<String, Object> details) {
    bestEffort(() ->
        success(
            action, targetType, targetId, targetName, projectId, projectName, summary, details));
  }

  /**
   * Records a refused or failed request from the response filter. Only authenticated callers are
   * recorded; the filter maps 403 to DENIED and 409/502 to FAILURE. The write is best-effort so it
   * can never replace the original response.
   */
  void recordFailure(
      Audited audited, AuditOutcome outcome, ContainerRequestContext request, int status) {
    AuditActor actor = context.actor();
    if (actor.subject() == null || actor.subject().isBlank()) {
      return;
    }
    MultivaluedMap<String, String> params = request.getUriInfo().getPathParameters();
    String targetId = first(params, "buildId", "id", "username", "name", "slug");
    String targetName = first(params, "username", "name", "slug");
    Long projectId = number(first(params, "projectId"));
    String method = AuditContext.clean(request.getMethod(), 10);
    String path = AuditContext.clean(request.getUriInfo().getRequestUri().getPath(), 512);
    String requestId =
        AuditContext.clean(request.getHeaderString(AuditContext.REQUEST_ID_HEADER), 64);
    bestEffort(() -> {
      AuditEvent event =
          write(
              audited.action(),
              outcome,
              audited.target(),
              targetId,
              targetName,
              projectId,
              null,
              audited.summary(),
              AuditDetails.of("status", status));
      event.httpMethod = method;
      event.path = path;
      event.requestId = requestId;
      event.statusCode = status;
      return event;
    });
  }

  private void bestEffort(java.util.function.Supplier<AuditEvent> write) {
    try {
      QuarkusTransaction.requiringNew()
          .run(() -> {
            write.get();
          });
    } catch (RuntimeException e) {
      Log.warnf(e, "Could not write an audit event; the original operation was not changed");
    }
  }

  private AuditEvent write(
      AuditAction action,
      AuditOutcome outcome,
      AuditTargetType targetType,
      Object targetId,
      String targetName,
      Long projectId,
      String projectName,
      String summary,
      Map<String, Object> details) {
    AuditActor actor = context.actor();
    AuditEvent event = new AuditEvent();
    event.occurredAt = Instant.now();
    event.action = action.code();
    event.outcome = outcome;
    event.actorSubject = AuditContext.clean(actor.subject(), 255);
    event.actorUsername = AuditContext.clean(AuditDetails.sanitizeText(actor.username()), 255);
    event.actorRoles =
        actor.roles().isEmpty() ? null : AuditContext.clean(String.join(",", actor.roles()), 255);
    event.projectId = projectId;
    event.projectName = AuditContext.clean(AuditDetails.sanitizeText(projectName), 100);
    event.targetType = targetType;
    event.targetId = AuditContext.clean(targetId == null ? null : String.valueOf(targetId), 255);
    event.targetName = AuditContext.clean(AuditDetails.sanitizeText(targetName), 255);
    event.summary =
        AuditContext.clean(
            AuditDetails.sanitizeText(
                summary == null ? action.code() : summary.replaceAll("\\s+", " ")),
            500);
    event.details = AuditDetails.sanitize(details);
    event.requestId = context.requestId();
    event.httpMethod = context.method();
    event.path = context.path();
    event.persist();
    return event;
  }

  private static String first(MultivaluedMap<String, String> params, String... names) {
    for (String name : names) {
      String value = params.getFirst(name);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static Long number(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Long.valueOf(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
