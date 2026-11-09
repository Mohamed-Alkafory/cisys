/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.util.FileUtils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Read-only JGit operations shared by the REST API and, later, by the workflow support.
 * Keeping the Git access here means callers never have to talk to JGit directly.
 */
@ApplicationScoped
public class GitRepositoryService {
    static final int TIMEOUT_SECONDS = 10;

    /** Branches of the project's repository. Private projects need credentials, so they are not read. */
    public List<String> listBranches(Project project) {
        if (project.privateRepository) return List.of();
        return listBranches(project.repositoryUrl);
    }

    /** Branches of an HTTPS github.com or gitlab.com repository. */
    public List<String> listBranches(String repositoryUrl) {
        validate(repositoryUrl);
        try {
            return branchNames(remoteBranchRefs(repositoryUrl));
        } catch (GitAPIException | RuntimeException e) {
            throw new BranchLookupException("Unable to read branches from the repository", e);
        }
    }

    /** The remote refs used for branch discovery. Overridden in tests so they never reach the network. */
    protected Collection<String> remoteBranchRefs(String repositoryUrl) throws GitAPIException {
        Path dir = null;
        try {
            dir = Files.createTempDirectory("cisys-branches-");
            try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
                var cfg = git.getRepository().getConfig();
                cfg.setString("http", null, "followRedirects", "false");
                cfg.save();
                return git.lsRemote()
                        .setRemote(repositoryUrl)
                        .setHeads(true)
                        .setTimeout(TIMEOUT_SECONDS)
                        .call()
                        .stream()
                        .map(Ref::getName)
                        .toList();
            }
        } catch (IOException e) {
            throw new GitAPIException("Unable to prepare the repository", e) {};
        } finally {
            if (dir != null) {
                try {
                    FileUtils.delete(dir.toFile(), FileUtils.RECURSIVE | FileUtils.RETRY);
                } catch (IOException ignored) {
                    // The temporary directory is not part of the result.
                }
            }
        }
    }

    /**
     * Accepts the same repositories that builds accept: HTTPS github.com or gitlab.com without
     * credentials, query strings, fragments or non-default ports.
     */
    static void validate(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank() || repositoryUrl.length() > 2048) {
            throw new IllegalArgumentException("Repository URL is required");
        }
        URI uri;
        try {
            uri = URI.create(repositoryUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Use an HTTPS github.com or gitlab.com repository URL");
        }
        if (!"https".equals(uri.getScheme())
                || !List.of("github.com", "gitlab.com").contains(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getPath() == null
                || uri.getPath().isEmpty()
                || uri.getPath().equals("/")) {
            throw new IllegalArgumentException("Use an HTTPS github.com or gitlab.com repository URL");
        }
    }

    /** Keeps only local branch refs, without duplicates, sorted. */
    static List<String> branchNames(Collection<String> refs) {
        TreeSet<String> names = new TreeSet<>();
        for (String ref : refs) {
            if (ref == null || !ref.startsWith("refs/heads/")) continue;
            String name = ref.substring("refs/heads/".length());
            if (!name.isBlank()) names.add(name);
        }
        return List.copyOf(names);
    }

    /** The remote repository could not be read. */
    public static class BranchLookupException extends RuntimeException {
        public BranchLookupException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
