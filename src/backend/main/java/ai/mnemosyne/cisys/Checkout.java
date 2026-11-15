/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.BooleanSupplier;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CoreConfig;
import org.eclipse.jgit.lib.EmptyProgressMonitor;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PackParser;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeOptions;

/**
 * Fetches one branch at depth 1 into a new directory, on that branch, with {@code origin} set to
 * the repository. The received pack is capped while it is read, the files the checkout will write
 * are measured before anything is checked out, and the written working tree is measured again
 * afterwards.
 */
final class Checkout {
  private static final long MIB = 1024 * 1024;

  private Checkout() {}

  static String fetch(
      Path dir,
      String url,
      String branch,
      CredentialsProvider credentials,
      boolean verifyTls,
      int maxMib,
      int maxFiles)
      throws Exception {
    return fetch(dir, url, branch, credentials, verifyTls, maxMib, maxFiles, null, () -> false);
  }

  static String fetch(
      Path dir,
      String url,
      String branch,
      CredentialsProvider credentials,
      boolean verifyTls,
      int maxMib,
      int maxFiles,
      BooleanSupplier cancelled)
      throws Exception {
    return fetch(dir, url, branch, credentials, verifyTls, maxMib, maxFiles, null, cancelled);
  }

  /**
   * Returns the commit ID. {@code dir} must not exist; it is removed again when the checkout fails.
   * The fetch stops once {@code cancelled} returns true. When {@code expectedRevision} is set, the
   * checkout is that commit, not the branch's current tip, so a poll build builds exactly the
   * commit the branch pointed at when it was seen.
   */
  static String fetch(
      Path dir,
      String url,
      String branch,
      CredentialsProvider credentials,
      boolean verifyTls,
      int maxMib,
      int maxFiles,
      String expectedRevision,
      BooleanSupplier cancelled)
      throws Exception {
    String ref = Constants.R_REMOTES + "origin/" + branch;
    String revision = normalizeRevision(expectedRevision);
    Files.createDirectory(dir);
    try (LimitedRepository repo =
            new LimitedRepository(dir.resolve(".git").toFile(), maxMib * MIB);
        Git git = Git.wrap(repo)) {
      repo.create();
      var cfg = repo.getConfig();
      cfg.setString("remote", "origin", "url", url);
      cfg.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*");
      cfg.setString("branch", branch, "remote", "origin");
      cfg.setString("branch", branch, "merge", Constants.R_HEADS + branch);
      GitRepositoryService.configureTransport(cfg, verifyTls);
      boolean localSource = url != null && url.startsWith("file:");
      boolean haveRevision = false;
      if (revision != null && localSource) {
        // A mirror keeps the commit after its branch is deleted or renamed, so ask for the exact
        // commit first instead of requiring the branch ref.
        try {
          fetch(git, url, new RefSpec(revision), credentials, cancelled, 1);
          haveRevision = has(repo, revision);
        } catch (Exception e) {
          if (repo.exceeded) throw tooLarge(maxMib);
        }
      }
      if (!haveRevision) {
        try {
          fetch(
              git,
              url,
              new RefSpec("+refs/heads/" + branch + ":" + ref),
              credentials,
              cancelled,
              1);
        } catch (Exception e) {
          if (repo.exceeded) throw tooLarge(maxMib);
          if (revision == null) throw e;
        }
      }
      String target = ref;
      if (revision != null) {
        target = revision;
        ObjectId resolved = repo.resolve(ref);
        if (!haveRevision && (resolved == null || !revision.equals(resolved.name()))) {
          // The branch moved after the push. Ask for the commit itself; providers that allow
          // reachable SHA fetches give us exactly that commit.
          try {
            fetch(git, url, new RefSpec(revision), credentials, cancelled, 1);
          } catch (Exception e) {
            if (repo.exceeded) throw tooLarge(maxMib);
          }
          if (!has(repo, revision)) {
            // Fall back to the branch's history when the provider does not allow SHA fetches.
            // The first fetch was shallow, so this one must also unshallow; fetching without a
            // depth otherwise leaves the old shallow boundary in place and the commit missing.
            try {
              fetch(
                  git,
                  url,
                  new RefSpec("+refs/heads/" + branch + ":" + ref),
                  credentials,
                  cancelled,
                  0);
            } catch (Exception e) {
              if (repo.exceeded) throw tooLarge(maxMib);
            }
          }
          if (!has(repo, revision))
            throw new IllegalArgumentException(
                "Commit " + revision + " is not reachable on branch " + branch);
        }
      }
      checkTree(repo, target, maxMib, maxFiles);
      if (revision == null) {
        git.checkout().setName(branch).setCreateBranch(true).setStartPoint(ref).call();
      } else {
        git.checkout().setName(revision).call();
      }
      // Smudge filters from the server's Git configuration can still change what is written.
      checkWritten(dir, maxMib, maxFiles);
      return repo.resolve(Constants.HEAD).name();
    } catch (Exception e) {
      try {
        BuildRunner.delete(dir);
      } catch (IOException suppressed) {
        e.addSuppressed(suppressed);
      }
      throw e;
    }
  }

  private static void fetch(
      Git git,
      String url,
      RefSpec spec,
      CredentialsProvider credentials,
      BooleanSupplier cancelled,
      int depth)
      throws Exception {
    var fetch =
        git.fetch()
            .setRemote(url)
            .setRefSpecs(spec)
            .setTimeout(60)
            .setProgressMonitor(
                new EmptyProgressMonitor() {
                  @Override
                  public boolean isCancelled() {
                    return cancelled.getAsBoolean();
                  }
                });
    if (depth > 0) fetch.setDepth(depth);
    else fetch.setUnshallow(true);
    if (credentials != null) fetch.setCredentialsProvider(credentials);
    fetch.call();
  }

  /** A full, lower-case SHA-1, or null when no revision was requested. */
  static String normalizeRevision(String revision) {
    if (revision == null || revision.isBlank()) return null;
    String value = revision.strip().toLowerCase(java.util.Locale.ROOT);
    if (!value.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid commit ID");
    return value;
  }

  private static boolean has(Repository repo, String revision) throws IOException {
    return repo.getObjectDatabase().has(ObjectId.fromString(revision));
  }

  /** Sizes are those of the files as checked out, after line ending conversion. */
  private static void checkTree(Repository repo, String ref, int maxMib, int maxFiles)
      throws IOException {
    long bytes = 0;
    int files = 0;
    WorkingTreeOptions options = repo.getConfig().get(WorkingTreeOptions.KEY);
    try (RevWalk revs = new RevWalk(repo);
        TreeWalk tree = new TreeWalk(repo)) {
      tree.setOperationType(TreeWalk.OperationType.CHECKOUT_OP);
      tree.addTree(revs.parseCommit(repo.resolve(ref)).getTree());
      tree.setRecursive(true);
      while (tree.next()) {
        if (++files > maxFiles) throw tooMany(maxFiles);
        // Submodule commits are not fetched.
        if (tree.getFileMode(0) == FileMode.GITLINK) continue;
        CoreConfig.EolStreamType eol = tree.getCheckoutEolStreamType(0);
        if (eol == null
            || eol == CoreConfig.EolStreamType.DIRECT
            || tree.getFileMode(0) == FileMode.SYMLINK) {
          bytes += tree.getObjectReader().getObjectSize(tree.getObjectId(0), Constants.OBJ_BLOB);
        } else {
          Counter written = new Counter(maxMib * MIB - bytes);
          try {
            DirCacheCheckout.getContent(
                repo,
                tree.getPathString(),
                new DirCacheCheckout.CheckoutMetadata(eol, null),
                tree.getObjectReader().open(tree.getObjectId(0), Constants.OBJ_BLOB),
                options,
                written);
          } catch (Counter.Exceeded e) {
            throw tooLarge(maxMib);
          }
          bytes += written.count;
        }
        if (bytes > maxMib * MIB) throw tooLarge(maxMib);
      }
    }
  }

  private static void checkWritten(Path dir, int maxMib, int maxFiles) throws IOException {
    Path git = dir.resolve(".git");
    long[] total = new long[2];
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
            return d.equals(git) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
            if (++total[0] > maxFiles) throw tooMany(maxFiles);
            total[1] += attrs.size();
            if (total[1] > maxMib * MIB) throw tooLarge(maxMib);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static IllegalArgumentException tooLarge(int maxMib) {
    return new IllegalArgumentException(
        "Repository checkout exceeds the limit of " + maxMib + " MiB");
  }

  private static IllegalArgumentException tooMany(int maxFiles) {
    return new IllegalArgumentException(
        "Repository checkout exceeds the limit of " + maxFiles + " files");
  }

  /** Counts what is written and stops once it passes {@code max}. */
  private static final class Counter extends OutputStream {
    static final class Exceeded extends IOException {}

    private final long max;
    long count;

    Counter(long max) {
      this.max = max;
    }

    @Override
    public void write(int b) throws IOException {
      add(1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      add(len);
    }

    private void add(long n) throws IOException {
      count += n;
      if (count > max) throw new Exceeded();
    }
  }

  private static final class LimitedRepository extends FileRepository {
    private final long maxBytes;
    private long received;
    boolean exceeded;

    LimitedRepository(File gitDir, long maxBytes) throws IOException {
      super(gitDir);
      this.maxBytes = maxBytes;
    }

    @Override
    public ObjectInserter newObjectInserter() {
      ObjectInserter inserter = super.newObjectInserter();
      return new ObjectInserter.Filter() {
        @Override
        protected ObjectInserter delegate() {
          return inserter;
        }

        @Override
        public PackParser newPackParser(InputStream in) throws IOException {
          return super.newPackParser(new Limited(in));
        }
      };
    }

    private final class Limited extends FilterInputStream {
      Limited(InputStream in) {
        super(in);
      }

      @Override
      public int read() throws IOException {
        int b = super.read();
        if (b >= 0) count(1);
        return b;
      }

      @Override
      public int read(byte[] buf, int off, int len) throws IOException {
        int n = super.read(buf, off, len);
        if (n > 0) count(n);
        return n;
      }

      @Override
      public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        if (skipped > 0) count(skipped);
        return skipped;
      }

      private void count(long n) throws IOException {
        received += n;
        if (received > maxBytes) {
          exceeded = true;
          throw new IOException("Pack exceeds the checkout size limit");
        }
      }
    }
  }
}
