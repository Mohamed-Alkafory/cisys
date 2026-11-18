/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THIS PROGRAM CONSTITUTING RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/**
 * A user assigned to a project, by Keycloak subject. Deleting the project removes the row. Team
 * roles are {@link TeamRole#MEMBER MEMBER} (read-only) and {@link TeamRole#LEAD LEAD} (read and
 * manage).
 */
@Entity
public class ProjectMember extends PanacheEntity {
  /**
   * The project roles: {@code MEMBER} may view and build; {@code LEAD} manages the project and its
   * team.
   */
  public enum TeamRole {
    MEMBER,
    LEAD
  }

  @Column(nullable = false)
  public Long projectId;

  @Column(nullable = false, length = 255)
  public String subject;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public TeamRole role = TeamRole.MEMBER;

  @Column(nullable = false)
  public Instant createdAt = Instant.now();

  /** Whether {@code subject} is assigned to the project. */
  static boolean isMember(Long projectId, String subject) {
    return subject != null && count("projectId = ?1 and subject = ?2", projectId, subject) > 0;
  }

  /** The role {@code subject} holds on the project, or null when they have no membership. */
  public static TeamRole roleOf(Long projectId, String subject) {
    if (subject == null) {
      return null;
    }
    return ProjectMember.<ProjectMember>find("projectId = ?1 and subject = ?2", projectId, subject)
        .firstResultOptional()
        .map(m -> m.role)
        .orElse(null);
  }

  /** True when {@code subject} is assigned with {@code required} or a higher role. */
  public static boolean hasRole(Long projectId, String subject, TeamRole required) {
    TeamRole have = roleOf(projectId, subject);
    return have != null && level(have) >= level(required);
  }

  private static int level(TeamRole role) {
    return role == TeamRole.LEAD ? 2 : 1;
  }

  /** The ids of the projects {@code subject} is assigned to, with any role. */
  static Set<Long> projectIds(String subject) {
    return new TreeSet<>(
        ProjectMember.<ProjectMember>list("subject", subject).stream()
            .map(m -> m.projectId)
            .toList());
  }

  /** The ids of the projects {@code subject} is assigned to with the given role. */
  public static Set<Long> projectIds(String subject, TeamRole required) {
    if (required == TeamRole.MEMBER) {
      return projectIds(subject);
    }
    return new TreeSet<>(
        ProjectMember.<ProjectMember>list("subject = ?1 and role = ?2", subject, TeamRole.LEAD)
            .stream()
            .map(m -> m.projectId)
            .toList());
  }

  /** The subjects assigned to any of the projects. */
  static Set<String> subjects(Collection<Long> projectIds) {
    if (projectIds.isEmpty()) {
      return Set.of();
    }
    return new TreeSet<>(
        ProjectMember.<ProjectMember>list("projectId in ?1", projectIds).stream()
            .map(m -> m.subject)
            .toList());
  }

  /** The number of LEADs in the project. Used to preserve the at-least-one-LEAD invariant. */
  public static long leads(Long projectId) {
    return count("projectId = ?1 and role = ?2", projectId, TeamRole.LEAD);
  }
}
