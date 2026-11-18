/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.logging.Log;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Discovers pull request and merge request heads and submits the ones above the project's baseline.
 * Only the head is built, only from the trusted mirror, and only when a local branch reaches it and
 * it has a workflow file. Discovery never fetches a pull request ref or a fork object into the
 * mirror. All methods run while the caller holds the project's {@link RepositoryMirror#lock(Long)}.
 */
@ApplicationScoped
public class PullRequestPoller {
  @Inject RepositoryMirror mirrors;
  @Inject GitRepositoryService git;
  @Inject BuildRunner builds;

  /** The newest pull requests that are tracked; older ones are ignored. */
  @ConfigProperty(name = "cisys.poll.max-pull-requests", defaultValue = "200")
  int maxPullRequests;

  /**
   * Projects whose last listing or mirror read failed; each is logged once until it works again.
   */
  private final Set<Long> listingFailures = ConcurrentHashMap.newKeySet();

  private final Set<Long> readFailures = ConcurrentHashMap.newKeySet();

  /**
   * Bumped by every settings change. A discovery pass that started before the change is discarded,
   * so it cannot seed cursors or a baseline from heads it read before the transition. One backend
   * instance runs discovery, so an in-memory generation is enough.
   */
  private final AtomicLong generation = new AtomicLong();

  /** What one discovered head looks like after the mirror checks. */
  record Head(String revision, String branch, boolean eligible) {}

  /** An eligible head that still has to be submitted. */
  record Candidate(int number, String revision, String branch) {}

  /**
   * Discovers the project's pull requests, advances cursors and submits eligible heads. Returns the
   * number of builds queued.
   */
  int pollProject(Project project) {
    long passGeneration = generation.get();
    String provider = ensureProvider(project);
    if (provider == null) {
      return 0;
    }
    Map<Integer, String> revisions;
    try {
      revisions = git.listPullRequests(project);
      listingFailures.remove(project.id);
    } catch (RuntimeException e) {
      if (listingFailures.add(project.id)) {
        Log.warnf(
            "Project %d: could not list pull requests; will retry: %s", project.id, message(e));
        Log.debugf(e, "Project %d pull request listing failure", project.id);
      }
      return 0;
    }
    // Only the newest N numbers are tracked; older ones are ignored by this feature.
    List<Integer> numbers = new ArrayList<>(revisions.keySet());
    numbers.sort(Comparator.reverseOrder());
    if (maxPullRequests > 0 && numbers.size() > maxPullRequests) {
      numbers = numbers.subList(0, maxPullRequests);
    }
    Map<Integer, Head> heads = new TreeMap<>();
    try {
      Map<String, String> tips = mirrors.branchTips(project.id);
      for (Integer number : numbers) {
        String revision = revisions.get(number);
        if (revision == null) {
          continue;
        }
        String branch = mirrors.branchContaining(project.id, revision, tips);
        boolean eligible = branch != null && mirrors.hasFile(project.id, revision, Workflow.PATH);
        heads.put(number, new Head(revision, branch, eligible));
      }
      readFailures.remove(project.id);
    } catch (Exception e) {
      // A mirror read failed: leave every cursor as it was, so nothing is lost or advanced.
      if (readFailures.add(project.id)) {
        Log.warnf(
            "Project %d: could not read pull request commits; will retry: %s",
            project.id, message(e));
        Log.debugf(e, "Project %d pull request mirror read failure", project.id);
      }
      return 0;
    }
    Set<String> eligibleRevisions = new HashSet<>();
    for (Head head : heads.values()) {
      if (head.eligible()) {
        eligibleRevisions.add(head.revision());
      }
    }
    List<Candidate> candidates =
        QuarkusTransaction.requiringNew()
            .call(() -> apply(project.id, heads, revisions.keySet(), passGeneration));
    // Reconcile before submitting so stale queued builds free queue capacity for this pass. The set
    // is the complete eligible head set, not the changed candidates, so a revision stays needed
    // while any current eligible pull request points at it.
    try {
      builds.reconcilePullRequestBuilds(project.id, eligibleRevisions);
    } catch (RuntimeException e) {
      Log.warnf(e, "Could not reconcile pull request builds of project %d", project.id);
    }
    int queued = 0;
    for (Candidate candidate : candidates) {
      if (submit(project, provider, candidate)) {
        queued++;
      }
    }
    return queued;
  }

  /** Stores the detected provider and resets the baseline when it changes. */
  String ensureProvider(Project project) {
    return QuarkusTransaction.requiringNew()
        .call(() -> {
          Project p = Project.findById(project.id);
          if (p == null) {
            return null;
          }
          PullRequestProvider detected = PullRequestProvider.from(p.repositoryUrl);
          // A local test repository has no provider host; its stored provider is used instead.
          if (detected == null) {
            detected = stored(p);
          }
          if (detected == null) {
            return null;
          }
          if (stored(p) != detected) {
            ProjectPullRequest.delete("projectId", p.id);
            p.pullRequestProvider = detected.name();
            p.pullRequestBaseline = null;
          }
          return detected.name();
        });
  }

  private static PullRequestProvider stored(Project p) {
    if (p.pullRequestProvider == null) {
      return null;
    }
    try {
      return PullRequestProvider.valueOf(p.pullRequestProvider);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /** Applies one discovery pass in a transaction and returns the heads to submit. */
  private List<Candidate> apply(
      Long projectId, Map<Integer, Head> heads, Set<Integer> present, long passGeneration) {
    // Serialize with the settings transition, which holds this row while it clears the cursors and
    // baseline. A pass that started before a change must not recreate either after it commits.
    Settings settings = Settings.findById(1L, LockModeType.PESSIMISTIC_WRITE);
    if (settings == null || !settings.pullRequestsEnabled) {
      return List.of();
    }
    if (generation.get() != passGeneration) {
      return List.of();
    }
    Project p = Project.findById(projectId);
    if (p == null) {
      return List.of();
    }
    List<Candidate> candidates = new ArrayList<>();
    Instant now = Instant.now();
    if (p.pullRequestBaseline == null) {
      // The first pass only seeds; nothing that already exists is built.
      int baseline = 0;
      for (Integer number : present) {
        baseline = Math.max(baseline, number);
      }
      p.pullRequestBaseline = baseline;
      for (Map.Entry<Integer, Head> entry : heads.entrySet()) {
        ProjectPullRequest cursor = ProjectPullRequest.of(projectId, entry.getKey());
        if (cursor == null) {
          cursor = new ProjectPullRequest();
        }
        seed(cursor, projectId, entry.getKey(), entry.getValue(), now);
        cursor.lastSubmittedRevision = entry.getValue().revision();
        cursor.persist();
      }
      markMissing(projectId, present);
      return candidates;
    }
    for (Map.Entry<Integer, Head> entry : heads.entrySet()) {
      int number = entry.getKey();
      Head head = entry.getValue();
      ProjectPullRequest cursor = ProjectPullRequest.of(projectId, number);
      if (cursor == null) {
        cursor = new ProjectPullRequest();
        seed(cursor, projectId, number, head, now);
        // A number at or below the baseline existed when the feature was enabled: seed it.
        if (number <= p.pullRequestBaseline) {
          cursor.lastSubmittedRevision = head.revision();
        }
        cursor.persist();
      } else {
        cursor.lastSeenRevision = head.revision();
        cursor.branch = head.branch();
        cursor.refPresent = true;
        cursor.seenAt = now;
      }
      // The baseline only decides which heads are seeded without a build. A seeded pull request
      // that receives a new head afterwards is still built once.
      if (head.eligible() && !head.revision().equals(cursor.lastSubmittedRevision)) {
        candidates.add(new Candidate(number, head.revision(), head.branch()));
      }
    }
    markMissing(projectId, present);
    return candidates;
  }

  private static void seed(
      ProjectPullRequest cursor, Long projectId, int number, Head head, Instant now) {
    cursor.projectId = projectId;
    cursor.number = number;
    cursor.lastSeenRevision = head.revision();
    cursor.lastSubmittedRevision = null;
    cursor.branch = head.branch();
    cursor.refPresent = true;
    cursor.seenAt = now;
  }

  /** A ref that disappeared keeps its row, so a restored ref with the same head builds nothing. */
  private static void markMissing(Long projectId, Set<Integer> present) {
    Set<Integer> known = new HashSet<>(present);
    for (ProjectPullRequest cursor :
        ProjectPullRequest.<ProjectPullRequest>list("projectId", projectId)) {
      if (!known.contains(cursor.number)) {
        cursor.refPresent = false;
      }
    }
  }

  /**
   * Queues one eligible head. A submission failure, including a full queue, leaves the cursor so
   * the next tick retries it. The cursor advances only after the build was queued or an existing
   * build for the same commit matched.
   */
  private boolean submit(Project project, String provider, Candidate candidate) {
    try {
      BuildRunner.Submission submission =
          builds.submitPullRequest(
              project.id,
              project.repositoryUrl,
              candidate.branch(),
              candidate.revision(),
              candidate.number(),
              provider);
      advance(project.id, candidate.number(), candidate.revision());
      return submission.created();
    } catch (WebApplicationException e) {
      if (CommitPoller.queueFull(e)) {
        Log.debugf(
            "Project %d pull request %d: the build queue is full; will retry",
            project.id, (Object) candidate.number());
      } else {
        Log.warnf(
            e,
            "Project %d pull request %d: submission failed; will retry",
            project.id,
            candidate.number());
      }
    } catch (RuntimeException e) {
      Log.warnf(
          e,
          "Project %d pull request %d: submission failed; will retry",
          project.id,
          candidate.number());
    }
    return false;
  }

  private void advance(Long projectId, int number, String revision) {
    QuarkusTransaction.requiringNew()
        .run(() ->
            ProjectPullRequest.update(
                "lastSubmittedRevision = ?1 where projectId = ?2 and number = ?3",
                revision,
                projectId,
                number));
  }

  /** Discards any discovery pass that read heads before the setting changed. */
  void invalidate() {
    generation.incrementAndGet();
  }

  /** Clears one project's cursors and seed point; its builds and history are kept. */
  void disableProject(Long projectId) {
    QuarkusTransaction.requiringNew()
        .run(() -> {
          ProjectPullRequest.delete("projectId", projectId);
          Project p = Project.findById(projectId);
          if (p != null) {
            p.pullRequestBaseline = null;
          }
        });
  }

  private static String message(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
  }
}
