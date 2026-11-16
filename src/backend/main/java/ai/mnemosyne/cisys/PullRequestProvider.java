/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.util.Locale;

/** The pull request providers supported in 0.1.0, and the head ref each one uses. */
public enum PullRequestProvider {
  GITHUB("refs/pull/", "/head"),
  GITLAB("refs/merge-requests/", "/head");

  private final String prefix;
  private final String suffix;

  PullRequestProvider(String prefix, String suffix) {
    this.prefix = prefix;
    this.suffix = suffix;
  }

  /** The host name this provider supports, or null for a repository without one. */
  public static PullRequestProvider from(String repositoryUrl) {
    String host = RepositoryUrls.host(repositoryUrl);
    if (host == null) return null;
    host = host.toLowerCase(Locale.ROOT);
    if (host.equals("github.com")) return GITHUB;
    if (host.equals("gitlab.com")) return GITLAB;
    return null;
  }

  /** The head ref of a pull request, never the merge ref. */
  public String headRef(int number) {
    return prefix + number + suffix;
  }

  /** The pull request number of a head ref, or null when the ref is not this provider's head. */
  public Integer number(String ref) {
    if (ref == null || !ref.startsWith(prefix) || !ref.endsWith(suffix)) return null;
    String number = ref.substring(prefix.length(), ref.length() - suffix.length());
    if (number.isEmpty() || !number.chars().allMatch(c -> c >= '0' && c <= '9')) return null;
    try {
      return Integer.valueOf(number);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** A label safe to show in the UI and the API. */
  public String label(int number) {
    return this == GITLAB ? "MR !" + number : "PR #" + number;
  }
}
