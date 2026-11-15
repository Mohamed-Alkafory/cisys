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

/**
 * A branch the poller watches, and the last commit it queued for it. The row is created when the
 * branch is first seen, seeded with its tip, and advanced only after a commit was queued or was
 * already built. Removing the row makes the branch seed again on its next sighting.
 */
@Entity
public class ProjectBranch extends PanacheEntity {
  @Column(nullable = false)
  public Long projectId;

  @Column(nullable = false, length = 255)
  public String branch;

  @Column(nullable = false, length = 64)
  public String lastSeenRevision;

  /** The cursor of a branch, or null when the branch was never seen. */
  public static ProjectBranch of(Long projectId, String branch) {
    return find("projectId = ?1 and branch = ?2", projectId, branch).firstResult();
  }
}
