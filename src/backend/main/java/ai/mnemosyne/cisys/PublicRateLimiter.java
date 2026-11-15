/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The per-client budget for anonymous visitors. The counting and eviction live in {@link
 * WindowLimiter}, which the webhook failure throttle shares.
 */
@ApplicationScoped
public class PublicRateLimiter {
  @ConfigProperty(name = "cisys.public.rate-limit.requests", defaultValue = "120")
  int requests;

  @ConfigProperty(name = "cisys.public.rate-limit.window-seconds", defaultValue = "60")
  int windowSeconds;

  @ConfigProperty(name = "cisys.public.rate-limit.max-clients", defaultValue = "10000")
  int maxClients;

  private final WindowLimiter windows = new WindowLimiter();

  /**
   * Counts one request. Returns zero when it is allowed, otherwise the number of seconds the client
   * should wait before retrying. A non-positive request limit disables the limiter.
   */
  long acquire(String client, long nowMillis) {
    configure();
    return windows.acquire(client, nowMillis);
  }

  /** For tests. */
  void reset() {
    windows.reset();
  }

  private void configure() {
    windows.requests = requests;
    windows.windowSeconds = windowSeconds;
    windows.maxClients = maxClients;
  }
}
