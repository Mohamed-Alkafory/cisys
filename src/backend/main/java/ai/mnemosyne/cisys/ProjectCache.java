/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The files a project keeps from one build to the next, such as a local Maven repository. When the
 * project's cache is on, a build copies the stored cache to {@code $HOME/.cisys/<project name>}
 * before the steps run; after a passed build that is not a pull request build, that directory
 * replaces the stored cache. The stored cache is keyed by project id, so a rename keeps it.
 */
@ApplicationScoped
public class ProjectCache {
  @ConfigProperty(name = "cisys.cache.dir")
  String cacheDir;

  @ConfigProperty(name = "cisys.cache.max-mib", defaultValue = "2048")
  long maxMib;

  private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

  /** The stored cache of a project. */
  Path path(Long projectId) {
    return Path.of(cacheDir).resolve(String.valueOf(projectId));
  }

  private ReentrantLock lock(Long projectId) {
    return locks.computeIfAbsent(projectId, id -> new ReentrantLock());
  }

  /** Copies the stored cache into {@code target}, which is created; symbolic links stay links. */
  void copyIn(Long projectId, Path target) throws IOException {
    Files.createDirectories(target);
    ReentrantLock lock = lock(projectId);
    lock.lock();
    try {
      Path source = path(projectId);
      if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
        copy(source, target);
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Makes {@code saved}, the cache directory taken out of a build, the project's cache. Returns
   * false, keeping the old cache, when it is larger than {@code cisys.cache.max-mib}. {@code saved}
   * is moved, so it must be on the same filesystem as the cache directory or is copied.
   */
  boolean save(Long projectId, Path saved) throws IOException {
    if (maxMib > 0 && size(saved) > maxMib * 1024 * 1024) {
      return false;
    }
    Path dir = path(projectId);
    Files.createDirectories(dir.getParent());
    Path next = Files.createTempDirectory(dir.getParent(), projectId + ".next-");
    copy(saved, next);
    ReentrantLock lock = lock(projectId);
    lock.lock();
    try {
      Path old = dir.resolveSibling(projectId + ".old");
      if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
        BuildRunner.delete(old);
      }
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        Files.move(dir, old, StandardCopyOption.ATOMIC_MOVE);
      }
      Files.move(next, dir, StandardCopyOption.ATOMIC_MOVE);
      if (Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
        BuildRunner.delete(old);
      }
    } finally {
      lock.unlock();
    }
    return true;
  }

  /** Removes a project's cache. */
  void delete(Long projectId) {
    ReentrantLock lock = lock(projectId);
    lock.lock();
    try {
      Path dir = path(projectId);
      if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
        BuildRunner.delete(dir);
      }
    } catch (IOException ignored) {
    } finally {
      lock.unlock();
    }
  }

  /** Copies a tree without following symbolic links, which are copied as links. */
  private static void copy(Path from, Path to) throws IOException {
    Files.walkFileTree(
        from,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(to.resolve(from.relativize(dir).toString()));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(
                file,
                to.resolve(from.relativize(file).toString()),
                LinkOption.NOFOLLOW_LINKS,
                StandardCopyOption.REPLACE_EXISTING);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static long size(Path dir) throws IOException {
    long[] total = new long[1];
    Files.walkFileTree(
        dir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            total[0] += attrs.size();
            return FileVisitResult.CONTINUE;
          }
        });
    return total[0];
  }
}
