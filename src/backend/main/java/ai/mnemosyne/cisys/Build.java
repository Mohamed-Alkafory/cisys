/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.*;
import java.time.Instant;

/** A build. Its log is a large object in the unmapped logoid column, see {@link BuildLogs}. */
@Entity
public class Build extends PanacheEntity {
  public Long projectId;

  /** Shown to users; counts up within the project, while the id stays internal. */
  @Column(nullable = false)
  public Long number;

  public String requestedBy;

  public enum Status {
    QUEUED,
    RUNNING,
    PASSED,
    FAILED,
    CANCELLED
  }

  @Enumerated(EnumType.STRING)
  public Status status;

  public Instant requestedAt;

  @Column(length = 2048)
  public String repositoryUrl;

  public String revision;
  public String branch;

  /**
   * The full name of the local image the workflow's container resolved to, once the build has read
   * it.
   */
  public String image;

  /** The text of the branch's {@value Workflow#PATH}, as the build read it. */
  @Column(length = Workflow.MAX_BYTES)
  public String workflow;

  /**
   * The Dockerfile the build generated from the workflow and ran, as it was written. Docker builds
   * have two, the preparation first; see {@link BuildRunner#dockerfiles}.
   */
  @Column(length = MAX_DOCKERFILES)
  public String dockerfile;

  static final int MAX_DOCKERFILES = 2 * Workflow.MAX_CONTAINERFILE + 1000;

  public String credentialRef;

  @Column(length = 300)
  public String imageDigest;

  public Integer exitCode;

  @Column(nullable = false)
  public boolean logTruncated;

  /** Pinned builds are kept by retention cleanup. */
  @Column(nullable = false)
  public boolean pinned;

  /** Set on a running build; the runner stops it and records it as cancelled. */
  @Column(nullable = false)
  public boolean cancelRequested;

  public Instant startedAt;
  public Instant finishedAt;

  /**
   * Takes the project's next build number from its counter in the unmapped buildcounter column. The
   * counter row stays locked until the transaction ends, and a rollback gives the number back.
   */
  static long nextNumber(Long projectId) {
    return ((Number)
            getEntityManager()
                .createNativeQuery(
                    "update project set buildcounter = buildcounter + 1 where id = ?1"
                        + " returning buildcounter")
                .setParameter(1, projectId)
                .getSingleResult())
        .longValue();
  }
}
