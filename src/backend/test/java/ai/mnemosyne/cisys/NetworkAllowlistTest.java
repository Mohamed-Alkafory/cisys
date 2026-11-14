/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class NetworkAllowlistTest {
  @Test
  void acceptsNamesWildcardsAndPublicAddresses() {
    NetworkAllowlist a =
        NetworkAllowlist.of(
            List.of(
                " Registry.NPMjs.org ",
                "*.maven.org",
                "8.8.8.8",
                "203.0.114.0/24",
                "2606:4700::/32",
                "",
                "registry.npmjs.org"));
    assertEquals(
        List.of("registry.npmjs.org", "*.maven.org", "8.8.8.8", "203.0.114.0/24", "2606:4700::/32"),
        a.entries());
    assertTrue(a.allowsHost("registry.npmjs.org"));
    assertTrue(a.allowsHost("REGISTRY.npmjs.org."));
    assertTrue(a.allowsHost("repo1.maven.org"));
    assertFalse(a.allowsHost("maven.org"), "a wildcard covers subdomains only");
    assertFalse(a.allowsHost("evilmaven.org"));
    assertFalse(a.allowsHost("npmjs.org"));
    assertTrue(a.allowsHost("8.8.8.8"));
    assertTrue(a.allowsHost("203.0.114.9"));
    assertTrue(a.allowsHost("[2606:4700::1]"));
    assertFalse(a.allowsHost("8.8.4.4"));
  }

  @Test
  void ipLiteralsMatchOnlyAddressEntries() {
    NetworkAllowlist a = NetworkAllowlist.of(List.of("example.com"));
    assertFalse(a.allowsHost("93.184.215.14"));
  }

  @Test
  void rejectsMalformedEntries() {
    for (String bad :
        new String[] {
          "localhost",
          "exa mple.com",
          "*.com.",
          "**.example.com",
          "example.com/24",
          "http://example.com",
          "-a.example.com",
          "1.2.3",
          "999.1.1.1",
          "8.8.8.8/33",
          "x".repeat(254) + ".com",
          "**",
          "*.*",
          "alls",
          "*.",
          "a..b"
        }) {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class, () -> NetworkAllowlist.of(List.of(bad)), bad);
      assertTrue(e.getMessage().startsWith("Invalid network entry"), e.getMessage());
    }
  }

  @Test
  void rejectsEntriesCoveringProtectedAddresses() {
    for (String bad :
        new String[] {
          "127.0.0.1",
          "10.0.0.5",
          "169.254.169.254",
          "192.168.1.0/24",
          "10.0.0.0/8",
          "0.0.0.0/0",
          "8.0.0.0/4",
          "::1",
          "::/0",
          "fd00::1",
          "fe80::/10",
          "::ffff:127.0.0.1",
          "::ffff:10.1.2.3",
          "100.64.0.1",
          "64:ff9b::a00:1",
          "2002:a00:1::",
          "::a00:1"
        }) {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class, () -> NetworkAllowlist.of(List.of(bad)), bad);
      assertTrue(e.getMessage().contains("protected"), bad + ": " + e.getMessage());
    }
  }

  @Test
  void limitsTheNumberOfEntries() {
    List<String> many = new java.util.ArrayList<>();
    for (int i = 0; i <= NetworkAllowlist.MAX_ENTRIES; i++) many.add("h" + i + ".example.com");
    assertThrows(IllegalArgumentException.class, () -> NetworkAllowlist.of(many));
    assertEquals(
        NetworkAllowlist.MAX_ENTRIES,
        NetworkAllowlist.of(many.subList(0, NetworkAllowlist.MAX_ENTRIES)).entries().size());
  }

  @Test
  void anyAllowsEveryHostAndIsWrittenStarOrAll() {
    for (String any : new String[] {"*", "all", " ALL "}) {
      NetworkAllowlist a = NetworkAllowlist.of(List.of(any));
      assertEquals(List.of(NetworkAllowlist.ANY), a.entries(), any);
      assertTrue(a.allowsAny());
      assertTrue(a.allowsHost("example.org"));
      assertTrue(a.allowsHost("8.8.8.8"));
      assertTrue(a.allowsHost("[2606:4700::1]"));
    }
    assertEquals(List.of("*"), NetworkAllowlist.of(List.of("*", "all")).entries());
    assertFalse(NetworkAllowlist.of(List.of("example.org")).allowsAny());
  }

  @Test
  void singleEntriesAreNormalizedOrRejected() {
    assertEquals("registry.npmjs.org", NetworkAllowlist.entry(" Registry.NPMjs.org "));
    assertEquals("*", NetworkAllowlist.entry("All"));
    assertThrows(IllegalArgumentException.class, () -> NetworkAllowlist.entry(""));
    assertThrows(IllegalArgumentException.class, () -> NetworkAllowlist.entry(null));
    assertThrows(IllegalArgumentException.class, () -> NetworkAllowlist.entry("10.0.0.1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> NetworkAllowlist.of(Collections.nCopies(1, "not valid")));
  }

  @Test
  void protectedAddressesInEveryEncoding() throws Exception {
    for (String p :
        new String[] {
          "127.0.0.1",
          "10.1.1.1",
          "172.16.0.1",
          "192.168.0.1",
          "169.254.169.254",
          "100.64.0.1",
          "0.0.0.0",
          "::1",
          "::",
          "fe80::1",
          "fd00::1",
          "::ffff:127.0.0.1",
          "::7f00:1",
          "64:ff9b::7f00:1",
          "2002:7f00:1::",
          "2001::1"
        }) {
      assertTrue(NetworkAllowlist.isProtected(InetAddress.getByName(p)), p);
    }
    for (String p : new String[] {"8.8.8.8", "140.82.112.3", "2606:4700::1111"}) {
      assertFalse(NetworkAllowlist.isProtected(InetAddress.getByName(p)), p);
    }
  }

  @Test
  void literalNeverResolvesNames() {
    assertNull(NetworkAllowlist.literal("example.com"));
    assertNull(NetworkAllowlist.literal("999.1.1.1"));
    assertArrayEquals(new byte[] {8, 8, 8, 8}, NetworkAllowlist.literal("8.8.8.8").getAddress());
    assertEquals(16, NetworkAllowlist.literal("[2606:4700::1]").getAddress().length);
  }
}
