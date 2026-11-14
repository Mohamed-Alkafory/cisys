/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@QuarkusTest
class BuildNetworkTest {
  @Inject BuildRunner runner;
  @TempDir Path temp;
  String runtime;
  final NetworkAllowlist allowlist = NetworkAllowlist.of(List.of("registry.example.com"));

  @BeforeEach
  void setup() {
    runner = io.quarkus.arc.ClientProxy.unwrap(runner);
    runtime = runner.runtime;
  }

  @AfterEach
  void cleanup() {
    runner.runtime = runtime;
    runner.networkPorts = "443";
    TestHostResolver.reset();
  }

  @Test
  void emptyAllowlistMeansNoProxy() {
    runner.runtime = "podman";
    assertNull(runner.network(temp, NetworkAllowlist.EMPTY, s -> {}));
    assertFalse(Files.exists(temp.resolve("proxy")));
  }

  @Test
  void dockerFailsClosed() {
    runner.runtime = "docker";
    var e =
        assertThrows(
            IllegalArgumentException.class, () -> runner.network(temp, allowlist, s -> {}));
    assertEquals("Network access is not supported with docker", e.getMessage());
    assertFalse(Files.exists(temp.resolve("proxy")));
  }

  @Test
  void podmanStartsAProxyThatIsRemovedOnClose() throws Exception {
    runner.runtime = "podman";
    EgressProxy proxy = runner.network(temp, allowlist, s -> {});
    try {
      assertEquals(temp.resolve("proxy"), proxy.directory());
      assertTrue(Files.exists(proxy.directory().resolve(EgressProxy.SOCKET)));
    } finally {
      proxy.close();
    }
    assertFalse(Files.exists(temp.resolve("proxy")));
  }

  @Test
  void setupFailuresFailClosed() {
    runner.runtime = "podman";
    runner.networkPorts = "443,70000";
    var e =
        assertThrows(
            IllegalArgumentException.class, () -> runner.network(temp, allowlist, s -> {}));
    assertEquals("Network access could not be set up", e.getMessage());
    runner.networkPorts = "443";
    assertThrows(
        IllegalArgumentException.class,
        () -> runner.network(temp.resolve("missing"), allowlist, s -> {}));
    assertFalse(Files.exists(temp.resolve("missing")));
  }

  @Test
  void policyDeniesTheBackendAndItsServices() throws Exception {
    TestHostResolver.set("db.example.com", "203.0.114.70");
    Optional<String> configured = runner.datasourceUrl;
    runner.datasourceUrl =
        Optional.of("jdbc:postgresql://db.example.com:5432,[2001:db8::5]:5433/cisys");
    try {
      EgressProxy.Policy policy = runner.networkPolicy(allowlist);
      assertEquals(java.util.Set.of(443), policy.ports());
      assertTrue(policy.deniedHosts().contains("db.example.com"), policy.deniedHosts().toString());
      assertTrue(
          policy.deniedAddresses().contains(InetAddress.getByName("203.0.114.70")),
          policy.deniedAddresses().toString());
      for (var nic : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
        for (InetAddress a : java.util.Collections.list(nic.getInetAddresses())) {
          if (!(a instanceof java.net.Inet6Address))
            assertTrue(policy.deniedAddresses().contains(a), a.toString());
        }
      }
    } finally {
      runner.datasourceUrl = configured;
    }
  }

  @Test
  void hostsOfUrls() {
    assertEquals(List.of("a", "b"), BuildRunner.hostsOf("jdbc:postgresql://a:5432,b/db?x=1"));
    assertEquals(
        List.of("keycloak.example.com"),
        BuildRunner.hostsOf("https://user@Keycloak.example.com:8180/realms/cisys"));
    assertEquals(List.of("::1"), BuildRunner.hostsOf("http://[::1]:8080"));
    assertEquals(List.of(), BuildRunner.hostsOf("not a url"));
  }
}
