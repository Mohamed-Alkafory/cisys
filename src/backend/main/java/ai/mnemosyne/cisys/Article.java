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
import java.util.List;

/** A markdown article, for example a FAQ entry. Anyone may read it. */
@Entity
public class Article extends PanacheEntity {
  /** The longest title, in characters. */
  public static final int MAX_TITLE = 200;

  /** The longest body, in characters. */
  public static final int MAX_BODY = 100000;

  @Column(nullable = false, length = MAX_TITLE)
  public String title;

  @Column(nullable = false, length = MAX_BODY)
  public String body;

  /** The Keycloak subject of the author. */
  @Column(nullable = false, length = 255)
  public String authorSubject;

  @Column(nullable = false)
  public Instant createdAt;

  @Column(nullable = false)
  public Instant updatedAt;

  /** Every article, most recently changed first. */
  static List<Article> recent() {
    return list("order by updatedAt desc, id desc");
  }
}
