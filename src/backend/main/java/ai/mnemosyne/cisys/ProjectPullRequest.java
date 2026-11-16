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
import java.time.Instant;

/**
 * The discovery state of one pull request or merge request. The seen revision is stored separately
 * from the submitted one, so deleting an old build through retention can never make the same head
 * build twice. A row is seeded with both revisions set, so enabling the feature never builds the
 * pull requests that already exist.
 */
@Entity
public class ProjectPullRequest extends PanacheEntity {
  @Column(nullable = false)
  public Long projectId;

  @Column(nullable = false)
  public Integer number;

  /** The head found by the last discovery pass, or null when the ref was never seen. */
  @Column(length = 64)
  public String lastSeenRevision;

  /** The head already submitted or matched to an existing build; null until that happens. */
  @Column(length = 64)
  public String lastSubmittedRevision;

  /** The branch whose history reaches the head, when the head is same-repository. */
  @Column(length = 255)
  public String branch;

  @Column(nullable = false)
  public boolean refPresent = true;

  public Instant seenAt;

  /** The cursor for one pull request, or null when it was never discovered. */
  public static ProjectPullRequest of(Long projectId, Integer number) {
    return find("projectId = ?1 and number = ?2", projectId, number).firstResult();
  }
}
