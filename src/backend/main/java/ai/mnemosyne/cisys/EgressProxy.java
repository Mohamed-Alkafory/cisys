/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * The only way out of a build step. One proxy serves one build on a Unix socket that the step
 * reaches through a bind mount, while the step itself has no network. It accepts HTTP {@code
 * CONNECT host:port} only and tunnels to the destination when the port and host are allowed and no
 * address of the host is protected; everything else is denied and logged in the build log.
 */
final class EgressProxy implements AutoCloseable {
  static final String SOCKET = "proxy.sock";
  static final int MAX_CONNECTIONS = 64;
  static final int MAX_HEADER = 8192;
  static final long HEADER_MILLIS = 10_000;
  static final int CONNECT_MILLIS = 10_000;
  static final long IDLE_MILLIS = 300_000;

  /** Opens the upstream connection. Replaced in tests, which cannot reach a public address. */
  interface Connector {
    ByteChannel connect(InetSocketAddress address, int timeoutMillis) throws IOException;
  }

  static final Connector TCP =
      (address, timeout) -> {
        SocketChannel channel = SocketChannel.open();
        try {
          channel.socket().connect(address, timeout);
          return channel;
        } catch (IOException | RuntimeException e) {
          channel.close();
          throw e;
        }
      };

  /**
   * What may be reached. {@code deniedHosts} and {@code deniedAddresses} are the backend's own
   * interfaces and the internal services it uses; they are denied on top of {@link
   * NetworkAllowlist#PROTECTED}.
   */
  record Policy(
      NetworkAllowlist allowlist,
      Set<Integer> ports,
      Set<String> deniedHosts,
      Set<InetAddress> deniedAddresses) {
    Policy {
      deniedHosts =
          deniedHosts.stream()
              .map(h -> h.toLowerCase(Locale.ROOT))
              .collect(Collectors.toUnmodifiableSet());
      deniedAddresses =
          deniedAddresses.stream()
              .map(EgressProxy::unwrapped)
              .collect(Collectors.toUnmodifiableSet());
    }
  }

  private final Path directory;
  private final Policy policy;
  private final HostResolver resolver;
  private final Connector connector;
  private final Consumer<String> log;
  private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
  private final Semaphore slots = new Semaphore(MAX_CONNECTIONS);
  private final Set<Channel> open = ConcurrentHashMap.newKeySet();
  private ServerSocketChannel server;
  private boolean created;
  private volatile boolean closed;

  private EgressProxy(
      Path directory,
      Policy policy,
      HostResolver resolver,
      Connector connector,
      Consumer<String> log) {
    this.directory = directory;
    this.policy = policy;
    this.resolver = resolver;
    this.connector = connector;
    this.log = log;
  }

  /**
   * Creates {@code directory} with the socket in it and starts serving. The directory may be
   * traversed but not listed by the step user; its parent must be private to the backend. On
   * failure nothing is left behind.
   */
  static EgressProxy start(
      Path directory,
      Policy policy,
      HostResolver resolver,
      Connector connector,
      Consumer<String> log)
      throws IOException {
    EgressProxy proxy = new EgressProxy(directory, policy, resolver, connector, log);
    try {
      proxy.listen();
      return proxy;
    } catch (IOException | RuntimeException e) {
      proxy.close();
      throw e;
    }
  }

  Path directory() {
    return directory;
  }

  private void listen() throws IOException {
    Files.createDirectory(
        directory,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    created = true;
    Path socket = directory.resolve(SOCKET);
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(socket), MAX_CONNECTIONS);
    // The step's user is unrelated to the backend's, and rootless runtimes map it to a subordinate
    // UID.
    Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-rw-rw-"));
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx--x--x"));
    threads.submit(this::accept);
  }

  private void accept() {
    while (!closed) {
      SocketChannel client;
      try {
        client = server.accept();
      } catch (IOException e) {
        return;
      }
      if (!slots.tryAcquire()) {
        reply(client, "503 Service Unavailable");
        quietly(client);
        continue;
      }
      open.add(client);
      try {
        threads.submit(() -> {
          try {
            handle(client);
          } finally {
            slots.release();
          }
        });
      } catch (RuntimeException e) {
        slots.release();
        quietly(client);
      }
    }
  }

  private void handle(SocketChannel client) {
    ByteChannel upstream = null;
    ScheduledFuture<?> deadline =
        timer.schedule(() -> quietly(client), HEADER_MILLIS, TimeUnit.MILLISECONDS);
    try {
      ByteBuffer header = ByteBuffer.allocate(MAX_HEADER);
      int end = readHeader(client, header);
      if (end < 0) {
        reply(client, "400 Bad Request");
        return;
      }
      String request = new String(header.array(), 0, end + 2, StandardCharsets.ISO_8859_1);
      String[] parts = request.substring(0, request.indexOf("\r\n")).split(" ", -1);
      if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
        reply(client, "400 Bad Request");
        return;
      }
      if (!parts[0].equals("CONNECT")) {
        deny(client, "405 Method Not Allowed", parts[1], "only CONNECT is supported");
        return;
      }
      Target target = Target.parse(parts[1]);
      if (target == null) {
        deny(client, "400 Bad Request", parts[1], "not host:port");
        return;
      }
      InetAddress address;
      try {
        address = check(target);
      } catch (Denied e) {
        deny(client, e.status, parts[1], e.getMessage());
        return;
      }
      try {
        upstream = connector.connect(new InetSocketAddress(address, target.port()), CONNECT_MILLIS);
      } catch (IOException | RuntimeException e) {
        deny(client, "502 Bad Gateway", parts[1], "connection failed");
        return;
      }
      open.add(upstream);
      deadline.cancel(false);
      reply(client, "200 Connection established");
      // Bytes the client sent after the request head belong to the tunnel.
      ByteBuffer early = ByteBuffer.wrap(header.array(), end + 4, header.position() - end - 4);
      while (early.hasRemaining()) upstream.write(early);
      tunnel(client, upstream);
    } catch (IOException | RuntimeException e) {
      // The connection is closed below; a broken client or upstream ends only its own tunnel.
    } finally {
      deadline.cancel(false);
      quietly(client);
      if (upstream != null) quietly(upstream);
    }
  }

  /**
   * Returns the offset of the blank line ending the request head, or -1 when there is none within
   * the limit.
   */
  private static int readHeader(SocketChannel client, ByteBuffer header) throws IOException {
    while (header.hasRemaining()) {
      if (client.read(header) < 0) return -1;
      byte[] b = header.array();
      for (int i = 3; i < header.position(); i++) {
        if (b[i - 3] == '\r' && b[i - 2] == '\n' && b[i - 1] == '\r' && b[i] == '\n') return i - 3;
      }
    }
    return -1;
  }

  private record Target(String host, int port) {
    static Target parse(String value) {
      int colon = value.lastIndexOf(':');
      if (colon <= 0 || colon == value.length() - 1) return null;
      String host = value.substring(0, colon).toLowerCase(Locale.ROOT);
      if (host.startsWith("[") != host.endsWith("]")) return null;
      if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
      else if (host.contains(":")) return null;
      if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
      if (host.isEmpty()
          || host.length() > NetworkAllowlist.MAX_LENGTH
          || !host.matches("[a-z0-9.:_-]+")) return null;
      int port;
      try {
        port = Integer.parseInt(value.substring(colon + 1));
      } catch (NumberFormatException e) {
        return null;
      }
      return port < 1 || port > 65535 ? null : new Target(host, port);
    }
  }

  private static final class Denied extends Exception {
    final String status;

    Denied(String status, String reason) {
      super(reason, null, false, false);
      this.status = status;
    }
  }

  /** The address to connect to, checked once so a later DNS change cannot redirect the tunnel. */
  private InetAddress check(Target target) throws Denied {
    if (!policy.ports().contains(target.port()))
      throw new Denied("403 Forbidden", "port not allowed");
    if (!policy.allowlist().allowsHost(target.host()))
      throw new Denied("403 Forbidden", "host not allowed");
    if (policy.deniedHosts().contains(target.host()))
      throw new Denied("403 Forbidden", "protected destination");
    InetAddress literal = NetworkAllowlist.literal(target.host());
    InetAddress[] addresses;
    if (literal != null) {
      addresses = new InetAddress[] {literal};
    } else {
      try {
        addresses = resolver.resolve(target.host());
      } catch (UnknownHostException | RuntimeException e) {
        throw new Denied("502 Bad Gateway", "could not be resolved");
      }
    }
    if (addresses == null || addresses.length == 0)
      throw new Denied("502 Bad Gateway", "could not be resolved");
    // Every address is checked, not only the one used, so a name cannot mix a public and a
    // protected address.
    for (InetAddress address : addresses) {
      if (address == null) throw new Denied("502 Bad Gateway", "could not be resolved");
      if (NetworkAllowlist.isProtected(address)
          || policy.deniedAddresses().contains(unwrapped(address))) {
        throw new Denied("403 Forbidden", "protected destination");
      }
    }
    return addresses[0];
  }

  private void tunnel(SocketChannel client, ByteChannel upstream) throws IOException {
    AtomicLong last = new AtomicLong(System.nanoTime());
    ScheduledFuture<?> idle =
        timer.scheduleAtFixedRate(
            () -> {
              if (System.nanoTime() - last.get() > TimeUnit.MILLISECONDS.toNanos(IDLE_MILLIS)) {
                quietly(client);
                quietly(upstream);
              }
            },
            1,
            1,
            TimeUnit.SECONDS);
    try {
      var back =
          threads.submit(() -> {
            copy(upstream, client, last);
            return null;
          });
      copy(client, upstream, last);
      try {
        back.get();
      } catch (Exception e) {
        // The other direction ended with an error; both channels are closed by the caller.
      }
    } finally {
      idle.cancel(false);
    }
  }

  private static void copy(ByteChannel from, ByteChannel to, AtomicLong last) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(16384);
    try {
      while (from.read(buffer) >= 0) {
        last.set(System.nanoTime());
        buffer.flip();
        while (buffer.hasRemaining()) to.write(buffer);
        buffer.clear();
      }
    } finally {
      if (to instanceof SocketChannel s && s.isOpen()) {
        try {
          s.shutdownOutput();
        } catch (IOException ignored) {
          // Already closed by the other side.
        }
      }
    }
  }

  private void deny(SocketChannel client, String status, String target, String reason) {
    String shown = target.matches("[A-Za-z0-9.:\\[\\]_-]{1,300}") ? target : "<invalid target>";
    log.accept("==> Network denied: " + shown + " (" + reason + ")");
    reply(client, status);
  }

  private static void reply(SocketChannel client, String status) {
    ByteBuffer response =
        ByteBuffer.wrap(
            ("HTTP/1.1 "
                    + status
                    + "\r\n"
                    + (status.startsWith("200") ? "" : "Content-Length: 0\r\nConnection: close\r\n")
                    + "\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
    try {
      while (response.hasRemaining()) client.write(response);
    } catch (IOException ignored) {
      // The client went away.
    }
  }

  private void quietly(Channel channel) {
    open.remove(channel);
    try {
      channel.close();
    } catch (IOException ignored) {
      // Nothing more to release.
    }
  }

  private static InetAddress unwrapped(InetAddress address) {
    try {
      return InetAddress.getByAddress(RepositoryUrls.unwrap(address.getAddress()));
    } catch (UnknownHostException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /** Stops serving, ends every tunnel and removes the socket and its directory. */
  @Override
  public void close() {
    closed = true;
    if (server != null) quietly(server);
    for (Channel channel : open.toArray(Channel[]::new)) quietly(channel);
    threads.shutdownNow();
    timer.shutdownNow();
    try {
      threads.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    if (!created) return;
    try {
      Files.deleteIfExists(directory.resolve(SOCKET));
      Files.deleteIfExists(directory);
    } catch (IOException e) {
      throw new IllegalStateException("Could not remove the network proxy socket", e);
    }
  }
}
