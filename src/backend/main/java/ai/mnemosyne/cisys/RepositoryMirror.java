/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * One bare mirror per project, on a configurable root outside the temporary directory. The poller
 * fetches all branch heads with forced updates and no automatic garbage collection, so a commit a
 * queued build still needs stays readable even after it left the remote.
 */
@ApplicationScoped
public class RepositoryMirror {
  @Inject RepositoryUrlValidator repositoryUrls;

  @ConfigProperty(name = "cisys.poll.mirror-dir")
  String mirrorDir;

  @ConfigProperty(name = "cisys.poll.mirror-max-mib", defaultValue = "4096")
  int maxMib;

  /** Test-only: accept file repository URLs, which the request path never produces. */
  @ConfigProperty(name = "cisys.poll.allow-local", defaultValue = "false")
  boolean allowLocal;

  private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

  /** Test-only: when it returns true for an operation, that read fails as if the disk errored. */
  volatile java.util.function.BiPredicate<String, String> readFailure = (op, detail) -> false;

  private void checkReadable(String operation, String detail) throws IOException {
    if (readFailure.test(operation, detail))
      throw new IOException("injected " + operation + " failure");
  }

  Path root() {
    return Path.of(mirrorDir);
  }

  /** Rejects a mirror root under the temporary directory, which an OS may clear between runs. */
  static void validateMirrorDirectory(String configured, String temporary) {
    Path dir = Path.of(configured).toAbsolutePath().normalize();
    Path tmp = Path.of(temporary).toAbsolutePath().normalize();
    if (dir.startsWith(tmp))
      throw new IllegalStateException(
          "cisys.poll.mirror-dir must not be under " + tmp + ": " + dir);
  }

  @PostConstruct
  void checkConfiguration() {
    validateMirrorDirectory(mirrorDir, System.getProperty("java.io.tmpdir"));
  }

  /** Restricts a mirror directory to its owner where the filesystem supports it. */
  private static void ownerOnly(Path dir) {
    try {
      if (Files.getFileStore(dir).supportsFileAttributeView(PosixFileAttributeView.class))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
    } catch (IOException | UnsupportedOperationException ignored) {
    }
  }

  /** The bare mirror directory of a project. */
  public Path path(Long projectId) {
    return root().resolve(projectId + ".git");
  }

  /** Serializes every fetch, read and cleanup of one project's mirror. */
  public ReentrantLock lock(Long projectId) {
    return locks.computeIfAbsent(projectId, id -> new ReentrantLock());
  }

  boolean allowLocal() {
    return allowLocal;
  }

  boolean local(String repositoryUrl) {
    return allowLocal && repositoryUrl != null && repositoryUrl.startsWith("file:");
  }

  /** Thrown when a mirror passes its size limit; the project is stopped until it is cleaned up. */
  public static class TooLargeException extends RuntimeException {
    TooLargeException(long bytes) {
      super("The repository mirror is " + bytes + " bytes, over its size limit");
    }
  }

  /**
   * Fetches every branch head of the project into its mirror, creating it on first use and
   * validating the URL immediately before every fetch. Refs for branches that disappeared are
   * removed, but no garbage collection runs, so queued builds keep their commits.
   */
  void fetch(Project project) throws Exception {
    String url = project.repositoryUrl;
    boolean local = local(url);
    String cloneUrl = local ? url : RepositoryUrls.cloneUrl(url);
    if (local) {
      if (!Files.isDirectory(Path.of(java.net.URI.create(url))))
        throw new IllegalArgumentException("Local repository does not exist: " + url);
    } else {
      repositoryUrls.validate(cloneUrl);
    }
    Path dir = path(project.id);
    Files.createDirectories(root());
    ownerOnly(root());
    try (Git git = open(dir)) {
      ownerOnly(dir);
      StoredConfig cfg = git.getRepository().getConfig();
      cfg.setString("remote", "origin", "url", cloneUrl);
      cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/heads/*");
      GitRepositoryService.configureTransport(cfg, local || repositoryUrls.verifyTls(url));
      var fetch =
          git.fetch()
              .setRemote("origin")
              .setRefSpecs(new RefSpec("+refs/heads/*:refs/heads/*"))
              .setRemoveDeletedRefs(true)
              .setTimeout(60);
      UsernamePasswordCredentialsProvider credentials = credentials(project.credentialRef);
      if (credentials != null) fetch.setCredentialsProvider(credentials);
      fetch.call();
    }
    long bytes = size(dir);
    if (maxMib > 0 && bytes > maxMib * 1024L * 1024L) throw new TooLargeException(bytes);
  }

  private Git open(Path dir) throws Exception {
    if (Files.isDirectory(dir)) return Git.open(dir.toFile());
    try (Git git = Git.init().setBare(true).setDirectory(dir.toFile()).call()) {
      return Git.open(dir.toFile());
    }
  }

  private static UsernamePasswordCredentialsProvider credentials(String credentialRef) {
    if (credentialRef == null) return null;
    String token = System.getenv(credentialRef);
    if (token == null || token.isBlank()) throw new IllegalStateException("Missing Git credential");
    return new UsernamePasswordCredentialsProvider("oauth2", token);
  }

  /** The branch names in a mirrored project, sorted. */
  Set<String> branches(Long projectId) throws Exception {
    checkReadable("branches", null);
    try (Repository repo = openRead(projectId)) {
      Set<String> names = new TreeSet<>();
      for (Ref ref : repo.getRefDatabase().getRefsByPrefix("refs/heads/"))
        names.add(ref.getName().substring("refs/heads/".length()));
      return names;
    }
  }

  /** The tip commit of a branch, or null when the branch is gone. */
  String tip(Long projectId, String branch) throws Exception {
    checkReadable("tip", branch);
    try (Repository repo = openRead(projectId)) {
      ObjectId id = repo.resolve("refs/heads/" + branch);
      return id == null ? null : id.name();
    }
  }

  /** Whether the mirror still holds a commit. */
  boolean has(Long projectId, String revision) throws Exception {
    try (Repository repo = openRead(projectId)) {
      checkReadable("has", revision);
      return repo.getObjectDatabase().has(ObjectId.fromString(revision));
    }
  }

  /** Whether a commit's tree contains a file. */
  boolean hasFile(Long projectId, String revision, String path) throws Exception {
    try (Repository repo = openRead(projectId);
        RevWalk walk = new RevWalk(repo)) {
      checkReadable("hasFile", revision);
      RevCommit commit = walk.parseCommit(ObjectId.fromString(revision));
      try (TreeWalk tree = TreeWalk.forPath(repo, path, commit.getTree())) {
        return tree != null;
      }
    }
  }

  /**
   * The commits on a branch that are not reachable from {@code from}, oldest first. A branch whose
   * new tip does not contain {@code from} was rewritten, so only the new tip is returned. A missing
   * {@code from} is a definitive absence; a read that fails is thrown.
   */
  List<String> commitsSince(Long projectId, String branch, String from) throws Exception {
    try (Repository repo = openRead(projectId)) {
      checkReadable("commitsSince", branch);
      ObjectId tipId = repo.resolve("refs/heads/" + branch);
      if (tipId == null) return List.of();
      if (from == null) return List.of(tipId.name());
      ObjectId fromId;
      try {
        fromId = ObjectId.fromString(from);
      } catch (IllegalArgumentException e) {
        return List.of(tipId.name());
      }
      if (!repo.getObjectDatabase().has(fromId)) return List.of(tipId.name());
      try (RevWalk walk = new RevWalk(repo)) {
        RevCommit tipCommit = walk.parseCommit(tipId);
        RevCommit fromCommit = walk.parseCommit(fromId);
        if (!walk.isMergedInto(fromCommit, tipCommit)) return List.of(tipId.name());
        walk.reset();
        walk.markStart(tipCommit);
        walk.markUninteresting(fromCommit);
        List<String> commits = new ArrayList<>();
        for (RevCommit commit : walk) commits.add(commit.name());
        Collections.reverse(commits);
        return commits;
      }
    }
  }

  private Repository openRead(Long projectId) throws IOException {
    return new org.eclipse.jgit.internal.storage.file.FileRepository(path(projectId).toFile());
  }

  /** The size of a mirror on disk. */
  long size(Long projectId) {
    return size(path(projectId));
  }

  private static long size(Path dir) {
    if (!Files.isDirectory(dir)) return 0;
    long[] total = new long[1];
    try {
      Files.walkFileTree(
          dir,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              total[0] += attrs.size();
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException ignored) {
    }
    return total[0];
  }

  /**
   * Removes a project's mirror. The caller holds the project lock and checked for queued builds.
   */
  void delete(Long projectId) {
    Path dir = path(projectId);
    if (Files.exists(dir))
      try {
        BuildRunner.delete(dir);
      } catch (IOException ignored) {
      }
    // The lock stays in the map: removing it while this thread (or another) still holds it would
    // hand a second lock to the next caller.
  }

  /** Removes every mirror, for an instance that is shutting polling down. */
  void deleteAll() {
    Path root = root();
    if (!Files.isDirectory(root)) return;
    try (var entries = Files.list(root)) {
      for (Path path : entries.toList()) {
        try {
          BuildRunner.delete(path);
        } catch (IOException ignored) {
        }
      }
    } catch (IOException ignored) {
    }
    locks.clear();
  }
}
