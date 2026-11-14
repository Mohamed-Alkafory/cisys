/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EgressProxyTest {
  @TempDir Path temp;
  private final Map<String, String[]> dns = new ConcurrentHashMap<>();
  private final List<String> log = new CopyOnWriteArrayList<>();
  private final List<InetSocketAddress> connected = new CopyOnWriteArrayList<>();
  private ServerSocket echo;
  private ExecutorService echoThreads;
  private EgressProxy proxy;

  private final HostResolver resolver =
      host -> {
        String[] values = dns.get(host);
        if (values == null) throw new UnknownHostException(host);
        InetAddress[] result = new InetAddress[values.length];
        for (int i = 0; i < values.length; i++) result[i] = InetAddress.getByName(values[i]);
        return result;
      };

  /**
   * Records the checked address, then connects to the local echo server instead, as no public host
   * is reachable here.
   */
  private final EgressProxy.Connector connector =
      (address, timeout) -> {
        connected.add(address);
        if (address.getAddress().getHostAddress().equals("203.0.114.99"))
          throw new IOException("refused");
        SocketChannel c = SocketChannel.open();
        c.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), echo.getLocalPort()));
        return c;
      };

  @BeforeEach
  void setup() throws Exception {
    echo = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    echoThreads = Executors.newVirtualThreadPerTaskExecutor();
    echoThreads.submit(() -> {
      while (!echo.isClosed()) {
        Socket s = echo.accept();
        echoThreads.submit(() -> {
          try (s) {
            s.getInputStream().transferTo(s.getOutputStream());
          }
          return null;
        });
      }
      return null;
    });
    dns.put("registry.example.com", new String[] {"203.0.114.10"});
    dns.put("mixed.example.com", new String[] {"203.0.114.11", "10.0.0.5"});
    dns.put("loop.example.com", new String[] {"127.0.0.1"});
    dns.put("private.example.com", new String[] {"10.1.2.3"});
    dns.put("metadata.example.com", new String[] {"169.254.169.254"});
    dns.put("mapped.example.com", new String[] {"::ffff:127.0.0.1"});
    dns.put("self.example.com", new String[] {"203.0.114.50"});
    dns.put("refused.example.com", new String[] {"203.0.114.99"});
    dns.put("db.internal.example.com", new String[] {"203.0.114.60"});
    proxy = start(Set.of(InetAddress.getByName("203.0.114.50")));
  }

  private EgressProxy start(Set<InetAddress> denied) throws IOException {
    NetworkAllowlist allowlist =
        NetworkAllowlist.of(List.of("registry.example.com", "*.example.com", "8.8.8.8"));
    EgressProxy.Policy policy =
        new EgressProxy.Policy(allowlist, Set.of(443), Set.of("DB.internal.example.com"), denied);
    return EgressProxy.start(temp.resolve("proxy"), policy, resolver, connector, log::add);
  }

  @AfterEach
  void cleanup() throws Exception {
    proxy.close();
    echo.close();
    echoThreads.shutdownNow();
  }

  private SocketChannel open() throws IOException {
    SocketChannel c = SocketChannel.open(StandardProtocolFamily.UNIX);
    c.connect(UnixDomainSocketAddress.of(proxy.directory().resolve(EgressProxy.SOCKET)));
    return c;
  }

  private static void write(SocketChannel c, String text) throws IOException {
    ByteBuffer b = ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1));
    while (b.hasRemaining()) c.write(b);
  }

  /**
   * Reads up to the end of the response head and returns its status line. Reads byte by byte, so
   * tunnel data that arrives right behind the head is left for the next read.
   */
  private static String status(SocketChannel c) throws IOException {
    StringBuilder text = new StringBuilder();
    ByteBuffer b = ByteBuffer.allocate(1);
    while (text.indexOf("\r\n\r\n") < 0 && text.length() < 1024) {
      b.clear();
      if (c.read(b) < 0) break;
      text.append((char) (b.get(0) & 0xFF));
    }
    int end = text.indexOf("\r\n");
    return end < 0 ? text.toString() : text.substring(0, end);
  }

  private static String read(SocketChannel c, int length) throws IOException {
    ByteBuffer b = ByteBuffer.allocate(length);
    while (b.hasRemaining() && c.read(b) >= 0) {
      // Keep reading until the expected bytes arrived.
    }
    return new String(b.array(), 0, b.position(), StandardCharsets.UTF_8);
  }

  private String connect(String target) throws IOException {
    try (SocketChannel c = open()) {
      write(c, "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n");
      return status(c);
    }
  }

  @Test
  void tunnelsToAnAllowedHost() throws Exception {
    try (SocketChannel c = open()) {
      write(
          c,
          "CONNECT registry.example.com:443 HTTP/1.1\r\nHost: registry.example.com:443\r\n\r\nearly");
      assertEquals("HTTP/1.1 200 Connection established", status(c));
      assertEquals("early", read(c, 5), "bytes sent with the request head are forwarded");
      write(c, "hello");
      assertEquals("hello", read(c, 5));
    }
    assertEquals(
        List.of(new InetSocketAddress(InetAddress.getByName("203.0.114.10"), 443)), connected);
    assertTrue(log.isEmpty(), log.toString());
  }

  @Test
  void concurrentTunnelsStaySeparate() throws Exception {
    ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    List<Future<String>> results = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      String message = "message-" + i;
      results.add(
          pool.submit(() -> {
            try (SocketChannel c = open()) {
              write(c, "CONNECT registry.example.com:443 HTTP/1.1\r\n\r\n");
              assertEquals("HTTP/1.1 200 Connection established", status(c));
              write(c, message);
              return read(c, message.length());
            }
          }));
    }
    for (int i = 0; i < 20; i++) assertEquals("message-" + i, results.get(i).get());
    pool.shutdown();
  }

  @Test
  void deniesWhatThePolicyDoesNotAllow() throws Exception {
    assertEquals("HTTP/1.1 403 Forbidden", connect("other.org:443"));
    assertEquals("HTTP/1.1 403 Forbidden", connect("registry.example.com:22"));
    assertEquals("HTTP/1.1 403 Forbidden", connect("registry.example.com:80"));
    assertEquals("HTTP/1.1 403 Forbidden", connect("8.8.4.4:443"));
    assertEquals("HTTP/1.1 400 Bad Request", connect("registry.example.com"));
    assertEquals("HTTP/1.1 400 Bad Request", connect("registry.example.com:99999"));
    try (SocketChannel c = open()) {
      write(c, "GET http://registry.example.com/ HTTP/1.1\r\n\r\n");
      assertEquals("HTTP/1.1 405 Method Not Allowed", status(c));
    }
    assertTrue(connected.isEmpty(), connected.toString());
    assertTrue(
        log.contains("==> Network denied: other.org:443 (host not allowed)"), log.toString());
    assertTrue(
        log.contains("==> Network denied: registry.example.com:22 (port not allowed)"),
        log.toString());
  }

  @Test
  void protectedDestinationsAreAlwaysDenied() throws Exception {
    for (String host :
        new String[] {
          "loop.example.com",
          "private.example.com",
          "metadata.example.com",
          "mapped.example.com",
          "mixed.example.com",
          "self.example.com",
          "db.internal.example.com",
          "127.0.0.1",
          "[::1]",
          "169.254.169.254"
        }) {
      assertEquals("HTTP/1.1 403 Forbidden", connect(host + ":443"), host);
    }
    assertTrue(connected.isEmpty(), connected.toString());
    assertTrue(
        log.contains("==> Network denied: metadata.example.com:443 (protected destination)"),
        log.toString());
  }

  @Test
  void failuresDenyTheRequest() throws Exception {
    assertEquals("HTTP/1.1 502 Bad Gateway", connect("unknown.example.com:443"));
    assertEquals("HTTP/1.1 502 Bad Gateway", connect("refused.example.com:443"));
    assertTrue(
        log.contains("==> Network denied: unknown.example.com:443 (could not be resolved)"),
        log.toString());
    assertTrue(
        log.contains("==> Network denied: refused.example.com:443 (connection failed)"),
        log.toString());
    try (SocketChannel c = open()) {
      write(c, "x".repeat(EgressProxy.MAX_HEADER + 10));
      assertEquals("HTTP/1.1 400 Bad Request", status(c));
    }
  }

  @Test
  void logsNoUntrustedText() throws Exception {
    connect("evil\u001b[31m.org:443");
    assertTrue(
        log.contains("==> Network denied: <invalid target> (not host:port)"), log.toString());
  }

  @Test
  void socketIsReachableByOthersButItsDirectoryIsNotListable() throws Exception {
    assertEquals(
        "rwx--x--x",
        PosixFilePermissions.toString(Files.getPosixFilePermissions(proxy.directory())));
    assertEquals(
        "rw-rw-rw-",
        PosixFilePermissions.toString(
            Files.getPosixFilePermissions(proxy.directory().resolve(EgressProxy.SOCKET))));
  }

  @Test
  void closeEndsTunnelsAndRemovesTheSocket() throws Exception {
    SocketChannel c = open();
    write(c, "CONNECT registry.example.com:443 HTTP/1.1\r\n\r\n");
    assertEquals("HTTP/1.1 200 Connection established", status(c));
    proxy.close();
    assertEquals(-1, c.read(ByteBuffer.allocate(1)));
    c.close();
    assertFalse(Files.exists(proxy.directory()));
    assertThrows(IOException.class, this::open);
  }

  @Test
  void failedStartLeavesNothingBehindAndKeepsWhatItDidNotCreate() throws Exception {
    NetworkAllowlist allowlist = NetworkAllowlist.of(List.of("registry.example.com"));
    EgressProxy.Policy policy = new EgressProxy.Policy(allowlist, Set.of(443), Set.of(), Set.of());
    Path missingParent = temp.resolve("missing").resolve("proxy");
    assertThrows(
        IOException.class,
        () -> EgressProxy.start(missingParent, policy, resolver, connector, log::add));
    assertFalse(Files.exists(missingParent));
    Path taken = Files.createDirectory(temp.resolve("taken"));
    assertThrows(
        IOException.class, () -> EgressProxy.start(taken, policy, resolver, connector, log::add));
    assertTrue(Files.isDirectory(taken));
  }
}
