/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded per-client fixed-window counter shared by the public visitor limiter and the webhook
 * failure limiter. It lives in this process, so it resets on restart and is not shared between
 * instances; the runner is documented as a single-instance deployment. Expired windows are purged
 * when the map reaches {@code max-clients}; if it is still full, one live window is evicted so a
 * new client is never locked out and the memory a flood of addresses can use stays bounded.
 * Expired-window purging is throttled and the eviction itself is constant time, so a full map
 * cannot turn ordinary traffic into a scan of every entry on each request.
 */
final class WindowLimiter {
  private static final long PURGE_INTERVAL_MILLIS = 1_000L;

  private record Window(long start, AtomicInteger count) {}

  int requests;
  int windowSeconds;
  int maxClients;

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
  private volatile long lastPurge;

  /**
   * Counts one request. Returns zero when it is allowed, otherwise the number of seconds the client
   * should wait before retrying. A non-positive request limit disables the limiter.
   */
  long acquire(String client, long nowMillis) {
    if (requests <= 0) return 0;
    Window window = touch(client, nowMillis);
    if (window.count().incrementAndGet() <= requests) return 0;
    return retryAfter(window, nowMillis);
  }

  /** For tests. */
  void reset() {
    windows.clear();
    lastPurge = 0;
  }

  private Window touch(String client, long nowMillis) {
    purge(nowMillis);
    if (maxClients > 0 && windows.size() >= maxClients && !windows.containsKey(client)) {
      // The map is full of live windows. Evict one and admit the new client: a client that
      // rotates addresses can bypass the limiter either way, so rejecting new clients would
      // only let a flood lock legitimate visitors out. Evicting the first entry the iterator
      // returns keeps this O(1); "oldest" is not important for a fail-open limiter.
      Iterator<String> candidates = windows.keySet().iterator();
      if (candidates.hasNext()) windows.remove(candidates.next());
    }
    return windows.compute(
        client,
        (key, current) ->
            current == null || expired(current, nowMillis)
                ? new Window(nowMillis, new AtomicInteger())
                : current);
  }

  private boolean expired(Window window, long nowMillis) {
    return nowMillis - window.start() >= windowMillis();
  }

  private long windowMillis() {
    return Math.max(1, windowSeconds) * 1000L;
  }

  private long retryAfter(Window window, long nowMillis) {
    long remaining = window.start() + windowMillis() - nowMillis;
    return Math.max(1, (remaining + 999) / 1000);
  }

  private void purge(long now) {
    if (maxClients <= 0 || windows.size() < maxClients) return;
    // A full map is checked on every request, so do the O(n) sweep at most once a second.
    if (now - lastPurge < PURGE_INTERVAL_MILLIS) return;
    lastPurge = now;
    windows.entrySet().removeIf(entry -> expired(entry.getValue(), now));
  }
}
