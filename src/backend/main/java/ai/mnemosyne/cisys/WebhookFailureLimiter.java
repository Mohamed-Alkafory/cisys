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
 * A separate per-project budget for failed webhook authentication. A failure is recorded after the
 * signature check, so a correctly signed delivery is never rejected because another request for the
 * same project failed; the global concurrent-work cap bounds how much unauthenticated work can run
 * at once. It is deliberately not part of {@code cisys.public.rate-limit.*}: webhook traffic must
 * never spend a visitor's budget. Counts reset after the window, so a burst of failures recovers on
 * its own.
 */
@ApplicationScoped
public class WebhookFailureLimiter {
  @ConfigProperty(name = "cisys.webhook.failure-limit.requests", defaultValue = "30")
  int requests;

  @ConfigProperty(name = "cisys.webhook.failure-limit.window-seconds", defaultValue = "60")
  int windowSeconds;

  @ConfigProperty(name = "cisys.webhook.failure-limit.max-clients", defaultValue = "10000")
  int maxClients;

  private final WindowLimiter windows = new WindowLimiter();

  /**
   * Atomically records one failed authentication. Returns zero while the project is under its
   * limit, otherwise the number of seconds before another attempt is accepted.
   */
  long recordFailure(String key) {
    return recordFailure(key, System.currentTimeMillis());
  }

  long recordFailure(String key, long nowMillis) {
    configure();
    return windows.acquire(key, nowMillis);
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
