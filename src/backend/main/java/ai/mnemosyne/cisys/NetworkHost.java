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
 * A destination build steps may reach through the egress proxy, managed by admins under Settings
 * &gt; Network. No hosts means no network.
 */
@Entity
public class NetworkHost extends PanacheEntity {
  @Column(nullable = false, length = 100)
  public String name;

  /** A {@link NetworkAllowlist} entry, in its normalized form. */
  @Column(nullable = false, length = NetworkAllowlist.MAX_LENGTH, unique = true)
  public String host;

  @Column(nullable = false, length = 255)
  public String createdBy;

  @Column(nullable = false)
  public Instant createdAt;

  /** All hosts as one allowlist. A stored host that no longer validates fails the build. */
  static NetworkAllowlist allowlist() {
    return NetworkAllowlist.of(
        NetworkHost.<NetworkHost>listAll().stream().map(h -> h.host).toList());
  }
}
