/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.ws.rs.WebApplicationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The pure parts of the webhook service: repository matching, branch globs and body limits. */
class WebhookServiceTest {
  private static final String STORED = "https://github.com/org/repo.git";
  private static final String CREDENTIALS = "https://user:token@github.com/org/repo.git/";
  private static final String PORT = "https://github.com:443/org/repo.git";
  private static final String QUERY = "https://github.com/org/repo.git?ref=main#frag";
  private static final String UPPER = "https://GitHub.com/Org/Repo.git";
  private static final String LOOKALIKE = "https://github.com.evil.example/org/repo.git";

  private static WebhookPayload.Repository repository(String fullName, String cloneUrl) {
    return new WebhookPayload.Repository(fullName, cloneUrl, null);
  }

  @Test
  void sameRepositoryAcceptsAMatchingCloneUrl() {
    assertTrue(WebhookService.sameRepository(STORED, repository(null, STORED)));
  }

  @Test
  void sameRepositoryAcceptsAMatchingFullNameWithoutACloneUrl() {
    assertTrue(WebhookService.sameRepository(STORED, repository("org/repo", null)));
  }

  @Test
  void sameRepositoryRejectsANullStoredUrlOrPayloadRepository() {
    assertFalse(WebhookService.sameRepository(null, repository("org/repo", null)));
    assertFalse(WebhookService.sameRepository(STORED, null));
  }

  @Test
  void sameRepositoryRejectsAnInvalidOrIncompleteStoredUrl() {
    assertFalse(WebhookService.sameRepository("", repository("org/repo", null)));
    assertFalse(WebhookService.sameRepository("   ", repository("org/repo", null)));
    assertFalse(WebhookService.sameRepository("not a url", repository("org/repo", null)));
    assertFalse(
        WebhookService.sameRepository("github.com/org/repo.git", repository("org/repo", null)));
  }

  @Test
  void sameRepositoryRejectsARepositoryWithNoLocationAtAll() {
    assertFalse(WebhookService.sameRepository(STORED, repository(null, null)));
  }

  @Test
  void sameRepositoryRejectsALookalikeHost() {
    assertFalse(WebhookService.sameRepository(STORED, repository("org/repo", LOOKALIKE)));
    assertFalse(
        WebhookService.sameRepository(
            STORED, repository("org/repo", "https://gitlab.com/org/repo.git")));
  }

  @Test
  void sameRepositoryRejectsAnExtraPathPrefixInThePayload() {
    // "evil/org/repo" is not the owner/repo pair GitHub reports for github.com/org/repo.
    assertFalse(WebhookService.sameRepository(STORED, repository("evil/org/repo", null)));
    assertFalse(
        WebhookService.sameRepository(
            STORED, repository("org/repo", "https://github.com/evil/org/repo.git")));
  }

  @Test
  void sameRepositoryRejectsANameThatOnlySharesTheStoredPathSuffix() {
    assertFalse(
        WebhookService.sameRepository(
            "https://github.com/org/repo-extra.git", repository("org/repo", null)));
    assertFalse(WebhookService.sameRepository(STORED, repository("other/repo", null)));
  }

  @Test
  void sameRepositoryRejectsAMismatchedFullNameEvenWhenTheCloneUrlMatches() {
    assertFalse(WebhookService.sameRepository(STORED, repository("org/other", STORED)));
  }

  @Test
  void sameRepositoryComparesOwnerAndPathCaseInsensitively() {
    assertTrue(WebhookService.sameRepository(UPPER, repository("ORG/REPO", STORED)));
  }

  @Test
  void sameRepositoryIgnoresAGitSuffixAndATrailingSlash() {
    assertTrue(
        WebhookService.sameRepository(
            "https://github.com/org/repo/", repository("org/repo", STORED)));
    assertTrue(
        WebhookService.sameRepository(STORED, repository(null, "https://github.com/org/repo/")));
  }

  @Test
  void sameRepositoryRequiresAnExactTwoSegmentFullName() {
    // GitHub reports exactly owner/repo; a nested stored path cannot match by suffix.
    assertFalse(
        WebhookService.sameRepository(
            "https://gitlab.com/group/sub/repo.git", repository("group/sub/repo", null)));
    assertFalse(
        WebhookService.sameRepository(
            STORED, repository("org/repo/extra", "https://github.com/org/repo.git")));
  }

  @Test
  void sameRepositoryRejectsAnExtraLeadingPathSegment() {
    assertFalse(
        WebhookService.sameRepository(
            "https://github.com/group/org/repo.git", repository("org/repo", null)));
  }

  @Test
  void sameRepositoryAcceptsTheCanonicalStoredUrlWithoutAGitSuffix() {
    assertTrue(
        WebhookService.sameRepository(
            "https://github.com/org/repo",
            repository("org/repo", "https://github.com/org/repo.git")));
    assertTrue(
        WebhookService.sameRepository(
            "https://github.com:443/org/repo",
            repository("org/repo", "https://github.com/org/repo.git")));
  }

  @Test
  void sameRepositoryTreatsANonDefaultPortAsPartOfTheRepository() {
    String port = "https://github.com:8443/org/repo.git";
    assertTrue(WebhookService.sameRepository(port, repository(null, port)));
    assertTrue(WebhookService.sameRepository(port, repository("org/repo", null)));
    assertFalse(WebhookService.sameRepository(port, repository(null, STORED)));
    assertFalse(WebhookService.sameRepository(STORED, repository(null, port)));
  }

  @Test
  void normalizeRepositoryReturnsAHostAndPathOnly() {
    assertEquals("github.com/org/repo", WebhookService.normalizeRepository(STORED));
    assertEquals("github.com/org/repo", WebhookService.normalizeRepository(CREDENTIALS));
    assertEquals("github.com/org/repo", WebhookService.normalizeRepository(PORT));
    assertEquals("github.com/org/repo", WebhookService.normalizeRepository(QUERY));
    assertEquals("github.com/org/repo", WebhookService.normalizeRepository(UPPER));
    assertEquals(
        "github.com:8443/org/repo",
        WebhookService.normalizeRepository("https://github.com:8443/org/repo.git"));
  }

  @Test
  void normalizeRepositoryKeepsNestedPaths() {
    assertEquals(
        "gitlab.com/group/sub/repo",
        WebhookService.normalizeRepository("https://gitlab.com/group/sub/repo/"));
  }

  @Test
  void normalizeRepositoryRejectsNullBlankAndInvalidUrls() {
    assertEquals("", WebhookService.normalizeRepository(null));
    assertEquals("", WebhookService.normalizeRepository(""));
    assertEquals("", WebhookService.normalizeRepository("   "));
    assertEquals("", WebhookService.normalizeRepository("not a url"));
    assertEquals("", WebhookService.normalizeRepository("github.com/org/repo.git"));
  }

  @Test
  void branchMatchesBlankPatternsAcceptEveryBranch() {
    assertTrue(WebhookService.branchMatches(null, "feature/x"));
    assertTrue(WebhookService.branchMatches("", "feature/x"));
    assertTrue(WebhookService.branchMatches("   ", "feature/x"));
    assertTrue(WebhookService.branchMatches("*", "feature/x"));
  }

  @Test
  void branchMatchesStarGlobsWithinOnePathSegment() {
    assertTrue(WebhookService.branchMatches("release/*", "release/1.0"));
    assertFalse(WebhookService.branchMatches("release/*", "release"));
    assertFalse(WebhookService.branchMatches("release/*", "feature/release/1.0"));
  }

  @Test
  void branchMatchesQuestionMarkGlobsExactlyOneCharacter() {
    assertTrue(WebhookService.branchMatches("v?", "v1"));
    assertFalse(WebhookService.branchMatches("v?", "v10"));
    assertFalse(WebhookService.branchMatches("v?", "v"));
    assertTrue(WebhookService.branchMatches("release/?,main", "release/1"));
    assertFalse(WebhookService.branchMatches("release/?,main", "release/10"));
  }

  @Test
  void branchMatchesAnExactPatternCaseSensitively() {
    assertTrue(WebhookService.branchMatches("main", "main"));
    assertFalse(WebhookService.branchMatches("main", "main2"));
    assertFalse(WebhookService.branchMatches("main", "Main"));
    assertFalse(WebhookService.branchMatches("main", "develop"));
  }

  @Test
  void branchMatchesWhitespaceAroundCommasAndSkipsEmptyPatterns() {
    assertTrue(WebhookService.branchMatches(" main , release/* ", "main"));
    assertTrue(WebhookService.branchMatches(" main , release/* ", "release/2.0"));
    assertTrue(WebhookService.branchMatches("main,,release/*", "release/3.0"));
    assertFalse(WebhookService.branchMatches("main,,release/*", "develop"));
  }

  @Test
  void branchMatchesTreatsPatternsAsLiteralsExceptForTheGlobCharacters() {
    assertTrue(WebhookService.branchMatches("a.b", "a.b"));
    assertFalse(WebhookService.branchMatches("a.b", "axb"));
    assertTrue(WebhookService.branchMatches("a+b", "a+b"));
    assertFalse(WebhookService.branchMatches("a+b", "aab"));
    assertTrue(WebhookService.branchMatches("[main]", "[main]"));
  }

  @Test
  void readReturnsTheOriginalBytes() throws Exception {
    byte[] body = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(
        body,
        WebhookService.read(new ByteArrayInputStream(body), WebhookService.DEFAULT_MAX_BYTES));
  }

  @Test
  void readAcceptsAnEmptyBody() throws Exception {
    assertEquals(
        0,
        WebhookService.read(new ByteArrayInputStream(new byte[0]), WebhookService.DEFAULT_MAX_BYTES)
            .length);
  }

  @Test
  void readAcceptsABodyExactlyAtTheLimit() throws Exception {
    byte[] body = new byte[WebhookService.DEFAULT_MAX_BYTES];
    body[0] = 'a';
    body[body.length - 1] = 'z';
    byte[] read =
        WebhookService.read(new ByteArrayInputStream(body), WebhookService.DEFAULT_MAX_BYTES);
    assertEquals(WebhookService.DEFAULT_MAX_BYTES, read.length);
    assertEquals((byte) 'a', read[0]);
    assertEquals((byte) 'z', read[read.length - 1]);
  }

  @Test
  void readRejectsABodyOneByteOverTheLimit() {
    byte[] body = new byte[WebhookService.DEFAULT_MAX_BYTES + 1];
    WebApplicationException e =
        assertThrows(
            WebApplicationException.class,
            () ->
                WebhookService.read(
                    new ByteArrayInputStream(body), WebhookService.DEFAULT_MAX_BYTES));
    assertEquals(413, e.getResponse().getStatus());
  }

  @Test
  void readUsesTheConfiguredLimit() throws Exception {
    byte[] body = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(body, WebhookService.read(new ByteArrayInputStream(body), 16));
    WebApplicationException e =
        assertThrows(
            WebApplicationException.class,
            () -> WebhookService.read(new ByteArrayInputStream(body), 15));
    assertEquals(413, e.getResponse().getStatus());
  }

  @Test
  void readPropagatesAFailingStream() {
    InputStream failing =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("boom");
          }
        };
    assertThrows(
        IOException.class, () -> WebhookService.read(failing, WebhookService.DEFAULT_MAX_BYTES));
  }
}
