/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The destinations build steps may reach through the egress proxy, set by admins under Settings
 * &gt; Network. An entry is a host name, {@code *.suffix} for every subdomain of a name, a public
 * IP address, a public CIDR range, or {@value #ANY} (also written {@code all}) for every public
 * destination. An empty list means no network.
 *
 * <p>{@link #PROTECTED} addresses are never reachable, whatever the list says, and no entry may
 * name them.
 */
record NetworkAllowlist(List<String> entries) {
  static final int MAX_ENTRIES = 100;
  static final int MAX_LENGTH = 253;

  /**
   * The repository blocklist plus the IPv6 forms that embed an IPv4 address (compatible, NAT64,
   * 6to4, Teredo).
   */
  static final List<RepositoryUrls.Cidr> PROTECTED;

  static {
    List<RepositoryUrls.Cidr> all = new ArrayList<>(RepositoryUrls.BLOCKED_NETWORKS);
    all.addAll(
        RepositoryUrls.parseNetworks("::/96,64:ff9b::/96,64:ff9b:1::/48,2002::/16,2001::/32"));
    PROTECTED = List.copyOf(all);
  }

  private static final String LABEL = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?";
  private static final String NAME = LABEL + "(\\." + LABEL + ")+";

  /** Every public destination; {@link #PROTECTED} addresses stay denied. */
  static final String ANY = "*";

  static final NetworkAllowlist EMPTY = new NetworkAllowlist(List.of());

  NetworkAllowlist {
    entries = List.copyOf(entries);
  }

  boolean isEmpty() {
    return entries.isEmpty();
  }

  boolean allowsAny() {
    return entries.contains(ANY);
  }

  /** The normalized form of one entry. Throws like {@link #of}. */
  static String entry(String value) {
    return of(List.of(value == null ? "" : value)).entries().stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("A host is required"));
  }

  /** Throws {@link IllegalArgumentException} with a message safe to show to an admin. */
  static NetworkAllowlist of(List<String> values) {
    if (values == null) return EMPTY;
    Set<String> result = new LinkedHashSet<>();
    for (String value : values) {
      if (value == null) continue;
      String entry = value.strip().toLowerCase(Locale.ROOT);
      if (entry.isEmpty()) continue;
      result.add(validate(entry));
    }
    if (result.size() > MAX_ENTRIES)
      throw new IllegalArgumentException("At most " + MAX_ENTRIES + " network entries are allowed");
    return new NetworkAllowlist(List.copyOf(result));
  }

  private static String validate(String entry) {
    if (entry.length() > MAX_LENGTH)
      throw new IllegalArgumentException("Invalid network entry: " + entry.substring(0, 40) + "…");
    if (entry.equals(ANY) || entry.equals("all")) return ANY;
    if (entry.contains("/")) {
      RepositoryUrls.Cidr cidr;
      try {
        if (literal(entry.substring(0, entry.indexOf('/'))) == null)
          throw new IllegalArgumentException();
        cidr = RepositoryUrls.Cidr.parse(entry);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Invalid network entry: " + entry);
      }
      for (RepositoryUrls.Cidr p : PROTECTED) {
        if (p.contains(cidr.network()) || cidr.contains(p.network())) throw protectedEntry(entry);
      }
      return entry;
    }
    InetAddress literal = literal(entry);
    if (literal != null) {
      if (isProtected(literal)) throw protectedEntry(entry);
      return literal.getHostAddress();
    }
    String name = entry.startsWith("*.") ? entry.substring(2) : entry;
    if (!name.matches(NAME) || name.matches("[0-9.]+"))
      throw new IllegalArgumentException("Invalid network entry: " + entry);
    return entry;
  }

  private static IllegalArgumentException protectedEntry(String entry) {
    return new IllegalArgumentException("Network entry " + entry + " covers protected addresses");
  }

  /**
   * Whether a CONNECT target names an allowed destination. IP literals match only IP and CIDR
   * entries.
   */
  boolean allowsHost(String target) {
    if (allowsAny()) return true;
    String host = target.toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
    InetAddress literal = literal(host);
    if (literal != null) return allowsAddress(literal);
    for (String entry : entries) {
      if (entry.startsWith("*.") ? host.endsWith(entry.substring(1)) : host.equals(entry))
        return true;
    }
    return false;
  }

  private boolean allowsAddress(InetAddress address) {
    byte[] raw = RepositoryUrls.unwrap(address.getAddress());
    for (String entry : entries) {
      if (entry.contains("/")) {
        if (RepositoryUrls.Cidr.parse(entry).contains(raw)) return true;
      } else {
        InetAddress listed = literal(entry);
        if (listed != null
            && java.util.Arrays.equals(RepositoryUrls.unwrap(listed.getAddress()), raw))
          return true;
      }
    }
    return false;
  }

  /**
   * Loopback, private, link-local, metadata and the other {@link #PROTECTED} ranges, in any
   * encoding.
   */
  static boolean isProtected(InetAddress address) {
    byte[] raw = address.getAddress();
    return RepositoryUrls.inside(raw, PROTECTED)
        || RepositoryUrls.inside(RepositoryUrls.unwrap(raw), PROTECTED);
  }

  /** Parses an IP literal without any DNS lookup, or returns null for anything else. */
  static InetAddress literal(String host) {
    String h =
        host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    boolean v4 = h.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    boolean v6 = h.contains(":") && h.matches("[0-9a-f:.]+");
    if (!v4 && !v6) return null;
    try {
      return InetAddress.ofLiteral(h);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
