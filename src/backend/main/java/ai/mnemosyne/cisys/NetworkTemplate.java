/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.util.List;
import java.util.Optional;

/** The hosts a well-known package registry needs, added together under Settings &gt; Network. */
public record NetworkTemplate(String id, String name, List<String> hosts) {
  static final List<NetworkTemplate> ALL =
      List.of(
          new NetworkTemplate("npm", "npm", List.of("registry.npmjs.org")),
          new NetworkTemplate("yarn", "Yarn", List.of("registry.yarnpkg.com", "repo.yarnpkg.com")),
          new NetworkTemplate(
              "maven", "Maven Central", List.of("repo.maven.apache.org", "repo1.maven.org")),
          new NetworkTemplate(
              "gradle",
              "Gradle",
              List.of(
                  "services.gradle.org",
                  "downloads.gradle.org",
                  "plugins.gradle.org",
                  "plugins-artifacts.gradle.org")),
          new NetworkTemplate("pypi", "PyPI", List.of("pypi.org", "files.pythonhosted.org")),
          new NetworkTemplate(
              "crates", "crates.io", List.of("crates.io", "index.crates.io", "static.crates.io")),
          new NetworkTemplate("go", "Go modules", List.of("proxy.golang.org", "sum.golang.org")),
          new NetworkTemplate(
              "rubygems", "RubyGems", List.of("rubygems.org", "index.rubygems.org")),
          new NetworkTemplate(
              "github",
              "GitHub",
              List.of(
                  "github.com",
                  "codeload.github.com",
                  "raw.githubusercontent.com",
                  "objects.githubusercontent.com",
                  "release-assets.githubusercontent.com")),
          new NetworkTemplate("all", "All public hosts", List.of(NetworkAllowlist.ANY)));

  static Optional<NetworkTemplate> find(String id) {
    return ALL.stream().filter(t -> t.id().equals(id)).findFirst();
  }
}
