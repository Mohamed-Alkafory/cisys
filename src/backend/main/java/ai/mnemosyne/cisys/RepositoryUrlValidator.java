/*
 * Eclipse Public License - v 2.0
 *
 *   THE ACCOMPANYING PROGRAM IS PROVIDED UNDER THE TERMS OF THIS ECLIPSE
 *   PUBLIC LICENSE ("AGREEMENT"). ANY USE, REPRODUCTION OR DISTRIBUTION
 *   OF THE PROGRAM CONSTITUTES RECIPIENT'S ACCEPTANCE OF THIS AGREEMENT.
 */

package ai.mnemosyne.cisys;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The configured repository rules: which hosts and ports may be fetched, which private networks an
 * administrator allowed on purpose, and which hosts may have a self-signed certificate. The check
 * runs at project creation, at build start and again immediately before the fetch, because DNS can
 * change after a project was saved.
 */
@ApplicationScoped
public class RepositoryUrlValidator {
  private final HostResolver resolver;
  private final Set<String> allowedHosts;
  private final Set<Integer> allowedPorts;
  private final List<RepositoryUrls.Cidr> allowedNetworks;
  private final Set<String> insecureTlsHosts;

  @Inject
  public RepositoryUrlValidator(
      HostResolver resolver,
      @ConfigProperty(name = "cisys.git.allowed-hosts", defaultValue = "github.com,gitlab.com")
          String allowedHosts,
      @ConfigProperty(name = "cisys.git.allowed-ports") Optional<String> allowedPorts,
      @ConfigProperty(name = "cisys.git.allowed-networks") Optional<String> allowedNetworks,
      @ConfigProperty(name = "cisys.git.insecure-tls-hosts") Optional<String> insecureTlsHosts) {
    this.resolver = resolver;
    this.allowedHosts = RepositoryUrls.parseHosts(allowedHosts);
    this.allowedPorts = RepositoryUrls.parsePorts(allowedPorts.orElse("443"));
    this.allowedNetworks = RepositoryUrls.parseNetworks(allowedNetworks.orElse(""));
    this.insecureTlsHosts = RepositoryUrls.parseHosts(insecureTlsHosts.orElse(""));
  }

  /** Throws {@link IllegalArgumentException} with a message safe to show to the caller. */
  public void validate(String repositoryUrl) {
    RepositoryUrls.validate(repositoryUrl, allowedHosts, allowedPorts, allowedNetworks, resolver);
  }

  /** False only for the hosts an administrator accepted a self-signed certificate for. */
  public boolean verifyTls(String repositoryUrl) {
    String host = RepositoryUrls.host(repositoryUrl);
    return host == null || !insecureTlsHosts.contains(host);
  }
}
