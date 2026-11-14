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

@Entity
public class Project extends PanacheEntity {
  @Column(nullable = false, length = 100, unique = true)
  public String name;

  @Column(nullable = false, length = 2048, unique = true)
  public String repositoryUrl;

  @Column(nullable = false)
  public boolean privateRepository;

  @Column(nullable = false, length = 255)
  public String ownerSubject;

  /** The environment variable that holds a read-only token for a private repository. */
  @Column(length = 100)
  public String credentialRef;

  /** Whether a verified push may start a build without a user asking for one. */
  @Column(nullable = false)
  public boolean webhookEnabled;

  /** The signing secret GitHub uses for this project's webhook. Never returned to non-owners. */
  @Column(length = 128)
  public String webhookSecret;

  /** Comma-separated branch globs; null or blank means every branch. */
  @Column(length = 1000)
  public String webhookBranches;
}
