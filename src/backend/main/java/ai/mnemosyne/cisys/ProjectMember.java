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
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/** A user assigned to a project, by Keycloak subject. Deleting the project removes the row. */
@Entity
public class ProjectMember extends PanacheEntity {
  @Column(nullable = false)
  public Long projectId;

  @Column(nullable = false, length = 255)
  public String subject;

  /** Whether {@code subject} is assigned to the project. */
  static boolean isMember(Long projectId, String subject) {
    return subject != null && count("projectId = ?1 and subject = ?2", projectId, subject) > 0;
  }

  /** The ids of the projects {@code subject} is assigned to. */
  static Set<Long> projectIds(String subject) {
    return new TreeSet<>(
        ProjectMember.<ProjectMember>list("subject", subject).stream()
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

  /**
   * Makes {@code subject}'s memberships among {@code scope} exactly {@code wanted}; projects
   * outside {@code scope} are left alone. A null scope is every project.
   */
  static void replace(String subject, Collection<Long> scope, Collection<Long> wanted) {
    if (scope == null) {
      delete("subject", subject);
    } else if (!scope.isEmpty()) {
      delete("subject = ?1 and projectId in ?2", subject, scope);
    }
    for (Long id : new TreeSet<>(wanted)) {
      if (scope != null && !scope.contains(id)) {
        continue;
      }
      ProjectMember m = new ProjectMember();
      m.projectId = id;
      m.subject = subject;
      m.persist();
    }
  }
}
