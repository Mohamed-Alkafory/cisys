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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** The built-in {@code list} report: the workflow's dependencies and the files of the checkout. */
@ApplicationScoped
public class ListReport implements ReportType {
  /** The most files listed, about twice the Linux kernel; the rest are counted. */
  static final int MAX_FILES = 200_000;

  @Override
  public String type() {
    return "list";
  }

  @Override
  public Report run(Context context) throws IOException {
    StringBuilder out = new StringBuilder("Dependencies\n");
    List<Workflow.Dependency> dependencies = context.workflow().dependencies();
    if (dependencies == null || dependencies.isEmpty()) {
      out.append("  none\n");
    } else {
      for (Workflow.Dependency d : dependencies) {
        out.append("  ").append(d.name()).append(": ").append(d.value()).append('\n');
      }
    }
    out.append("\nFiles\n");
    Path root = context.checkout();
    List<String> files;
    // Files.walk does not follow symbolic links, and a link is not listed as a file, so nothing
    // outside the checkout is read.
    try (Stream<Path> walk = Files.walk(root)) {
      files =
          walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
              .map(p -> root.relativize(p).toString().replace('\\', '/'))
              .filter(p -> !p.equals(".git") && !p.startsWith(".git/"))
              .sorted()
              .toList();
    }
    files.stream().limit(MAX_FILES).forEach(f -> out.append("  ").append(f).append('\n'));
    if (files.size() > MAX_FILES) {
      out.append("  ... and ").append(files.size() - MAX_FILES).append(" more\n");
    }
    out.append('\n').append(files.size()).append(files.size() == 1 ? " file\n" : " files\n");
    return new Report(Format.TEXT, out.toString());
  }
}
