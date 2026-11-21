/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One administrative or security-sensitive action. The actor, project and target names are
 * snapshots, and there are no foreign keys, so a row remains meaningful after its target is
 * deleted. Details only holds allowlisted, redacted values; see {@link AuditDetails}.
 */
@Entity
public class AuditEvent extends PanacheEntity {
  @Column(nullable = false)
  public Instant occurredAt;

  /** The stable {@link AuditAction#code() code} of the action. */
  @Column(nullable = false, length = 60)
  public String action;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public AuditOutcome outcome;

  @Column(length = 255)
  public String actorSubject;

  @Column(length = 255)
  public String actorUsername;

  /** Comma-separated snapshot of the caller's roles at the time. */
  @Column(length = 255)
  public String actorRoles;

  public Long projectId;

  @Column(length = 100)
  public String projectName;

  @Enumerated(EnumType.STRING)
  @Column(length = 40)
  public AuditTargetType targetType;

  @Column(length = 255)
  public String targetId;

  @Column(length = 255)
  public String targetName;

  @Column(nullable = false, length = 500)
  public String summary;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  public Map<String, Object> details = new LinkedHashMap<>();

  /** The caller's request id, when one was supplied; otherwise null. */
  @Column(length = 64)
  public String requestId;

  @Column(length = 10)
  public String httpMethod;

  @Column(length = 512)
  public String path;

  public Integer statusCode;
}
