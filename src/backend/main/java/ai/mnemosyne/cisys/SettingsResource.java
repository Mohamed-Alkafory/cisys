/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;

/** Admin settings. */
@Path("/api/settings")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SettingsResource {
  static final int MAX_DAYS = 3650,
      MAX_BUILDS = 10000,
      MAX_CONCURRENT = 16,
      MAX_QUEUED = 1000,
      MAX_TIMEOUT_MINUTES = 1440,
      MAX_CHECKOUT_MIB = 102400,
      MAX_CHECKOUT_FILES = 1000000,
      MIN_MEMORY_MIB = 64,
      MAX_MEMORY_MIB = 65536,
      MAX_CPUS = 16,
      MIN_PIDS = 16,
      MAX_PIDS = 32768;

  @Inject SecurityIdentity identity;
  @Inject BuildRunner runner;
  @Inject CommitPoller polls;

  /** 0 turns a limit off. */
  public record Retention(Integer maxAgeDays, Integer maxBuilds) {}

  /** The build queue and how long one build may take. */
  public record Builds(Integer maxConcurrent, Integer maxQueued, Integer timeoutMinutes) {}

  /** Limits on a build's repository checkout. */
  public record Checkout(Integer maxSizeMib, Integer maxFiles) {}

  /** Limits applied to every build container. */
  public record Performance(Integer maxMemoryMib, Integer maxCpus, Integer maxPids) {}

  /**
   * Automatic builds: on/off, per-commit or tip-only, all workflow branches or main/master only.
   */
  public record Polling(
      Boolean enabled,
      Settings.Granularity granularity,
      Settings.Scope scope,
      Integer intervalSeconds) {}

  /** The retention limits. */
  @GET
  @Path("/retention")
  public Retention retention() {
    requireAdmin();
    Settings s = Settings.current();
    return new Retention(s.retentionDays, s.retentionMaxBuilds);
  }

  /** Saves the retention limits. */
  @PUT
  @Path("/retention")
  @Transactional
  public Retention updateRetention(Retention input) {
    requireAdmin();
    if (input == null
        || input.maxAgeDays() == null
        || input.maxAgeDays() < 0
        || input.maxAgeDays() > MAX_DAYS)
      throw new BadRequestException("Days must be between 0 and " + MAX_DAYS);
    if (input.maxBuilds() == null || input.maxBuilds() < 0 || input.maxBuilds() > MAX_BUILDS)
      throw new BadRequestException("Builds must be between 0 and " + MAX_BUILDS);
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    s.retentionDays = input.maxAgeDays();
    s.retentionMaxBuilds = input.maxBuilds();
    return new Retention(s.retentionDays, s.retentionMaxBuilds);
  }

  /** The concurrency, queue and timeout limits for builds. */
  @GET
  @Path("/builds")
  public Builds builds() {
    requireAdmin();
    return new Builds(runner.concurrency(), runner.queueLimit(), runner.timeoutMinutes());
  }

  /** Saves the concurrency, queue and timeout limits. */
  @PUT
  @Path("/builds")
  @Transactional
  public Builds updateBuilds(Builds input) {
    requireAdmin();
    if (input == null
        || input.maxConcurrent() == null
        || input.maxConcurrent() < 1
        || input.maxConcurrent() > MAX_CONCURRENT)
      throw new BadRequestException("Concurrent builds must be between 1 and " + MAX_CONCURRENT);
    if (input.maxQueued() == null || input.maxQueued() < 1 || input.maxQueued() > MAX_QUEUED)
      throw new BadRequestException("Queued builds must be between 1 and " + MAX_QUEUED);
    if (input.timeoutMinutes() == null
        || input.timeoutMinutes() < 1
        || input.timeoutMinutes() > MAX_TIMEOUT_MINUTES)
      throw new BadRequestException(
          "Timeout must be between 1 and " + MAX_TIMEOUT_MINUTES + " minutes");
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    s.maxConcurrentBuilds = input.maxConcurrent();
    s.maxQueuedBuilds = input.maxQueued();
    s.buildTimeoutMinutes = input.timeoutMinutes();
    return new Builds(s.maxConcurrentBuilds, s.maxQueuedBuilds, s.buildTimeoutMinutes);
  }

  /** The checkout limits. */
  @GET
  @Path("/checkout")
  public Checkout checkout() {
    requireAdmin();
    return new Checkout(runner.maxCheckoutMib(), runner.maxCheckoutFiles());
  }

  /** Saves the checkout limits. */
  @PUT
  @Path("/checkout")
  @Transactional
  public Checkout updateCheckout(Checkout input) {
    requireAdmin();
    if (input == null
        || input.maxSizeMib() == null
        || input.maxSizeMib() < 1
        || input.maxSizeMib() > MAX_CHECKOUT_MIB)
      throw new BadRequestException(
          "Checkout size must be between 1 and " + MAX_CHECKOUT_MIB + " MiB");
    if (input.maxFiles() == null || input.maxFiles() < 1 || input.maxFiles() > MAX_CHECKOUT_FILES)
      throw new BadRequestException("Checkout files must be between 1 and " + MAX_CHECKOUT_FILES);
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    s.maxCheckoutMib = input.maxSizeMib();
    s.maxCheckoutFiles = input.maxFiles();
    return new Checkout(s.maxCheckoutMib, s.maxCheckoutFiles);
  }

  /** The container limits. */
  @GET
  @Path("/performance")
  public Performance performance() {
    requireAdmin();
    return new Performance(runner.maxMemoryMib(), runner.maxCpus(), runner.maxPids());
  }

  /** Saves the container limits. */
  @PUT
  @Path("/performance")
  @Transactional
  public Performance updatePerformance(Performance input) {
    requireAdmin();
    if (input == null
        || input.maxMemoryMib() == null
        || input.maxMemoryMib() < MIN_MEMORY_MIB
        || input.maxMemoryMib() > MAX_MEMORY_MIB)
      throw new BadRequestException(
          "Memory must be between " + MIN_MEMORY_MIB + " and " + MAX_MEMORY_MIB + " MiB");
    if (input.maxCpus() == null || input.maxCpus() < 1 || input.maxCpus() > MAX_CPUS)
      throw new BadRequestException("CPUs must be between 1 and " + MAX_CPUS);
    if (input.maxPids() == null || input.maxPids() < MIN_PIDS || input.maxPids() > MAX_PIDS)
      throw new BadRequestException("Processes must be between " + MIN_PIDS + " and " + MAX_PIDS);
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    s.maxBuildMemoryMib = input.maxMemoryMib();
    s.maxBuildCpus = input.maxCpus();
    s.maxBuildPids = input.maxPids();
    return new Performance(s.maxBuildMemoryMib, s.maxBuildCpus, s.maxBuildPids);
  }

  private static final int MIN_POLL_SECONDS = CommitPoller.MIN_INTERVAL_SECONDS,
      MAX_POLL_SECONDS = CommitPoller.MAX_INTERVAL_SECONDS;

  /** The automatic-build settings. */
  @GET
  @Path("/polling")
  public Polling polling() {
    requireAdmin();
    Settings s = Settings.current();
    return new Polling(s.pollEnabled, s.pollGranularity, s.pollScope, effectiveInterval(s));
  }

  /** Saves the automatic-build settings. */
  @PUT
  @Path("/polling")
  @Transactional
  public Polling updatePolling(Polling input) {
    requireAdmin();
    if (input == null || input.enabled() == null)
      throw new BadRequestException("Automatic builds must be on or off");
    if (input.granularity() == null) throw new BadRequestException("Granularity is required");
    if (input.scope() == null) throw new BadRequestException("Scope is required");
    if (input.intervalSeconds() != null
        && (input.intervalSeconds() < MIN_POLL_SECONDS
            || input.intervalSeconds() > MAX_POLL_SECONDS))
      throw new BadRequestException(
          "Poll interval must be between "
              + MIN_POLL_SECONDS
              + " and "
              + MAX_POLL_SECONDS
              + " seconds");
    Settings s = Settings.findById(1L);
    if (s == null) {
      s = new Settings();
      s.persist();
    }
    s.pollEnabled = input.enabled();
    s.pollGranularity = input.granularity();
    s.pollScope = input.scope();
    s.pollIntervalSeconds = input.intervalSeconds();
    return new Polling(s.pollEnabled, s.pollGranularity, s.pollScope, effectiveInterval(s));
  }

  /** The stored interval, or the configured default so the form always has a number. */
  private int effectiveInterval(Settings s) {
    return s.pollIntervalSeconds != null ? s.pollIntervalSeconds : polls.defaultIntervalSeconds();
  }

  private static WebApplicationException badRequest(String message) {
    return new WebApplicationException(
        Response.status(400)
            .type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message))
            .build());
  }

  private void requireAdmin() {
    if (!identity.hasRole("admin")) throw new ForbiddenException();
  }
}
