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
import java.util.List;

/** A report a build's workflow asked for, see {@link ReportType}. */
@Entity
public class BuildReport extends PanacheEntity {
  /** The longest content kept, in characters; longer content is cut and marked truncated. */
  public static final int MAX_CONTENT = 16 * 1024 * 1024;

  @Column(nullable = false)
  public Long buildId;

  /** The report type's key, such as {@code list}. */
  @Column(nullable = false, length = 50)
  public String type;

  /** The report's name from the workflow, shown as its tab. */
  @Column(nullable = false, length = Workflow.MAX_NAME)
  public String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 10)
  public ReportType.Format format;

  @Column(nullable = false, columnDefinition = "text")
  public String content;

  @Column(nullable = false)
  public boolean truncated;

  @Column(nullable = false)
  public Instant createdAt;

  /** The build's reports, in the order the workflow lists them. */
  static List<BuildReport> of(Long buildId) {
    return list("buildId = ?1 order by id", buildId);
  }
}
