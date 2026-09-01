package eu.wohlben.qits.maintenance.peer;

import io.quarkus.oidc.client.NamedOidcClient;
import io.quarkus.oidc.client.OidcClient;
import io.quarkus.oidc.client.runtime.TokensHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * The six named oidc clients — one per peer SERVICE — and the reason there are six.
 *
 * <p><b>A token is cut FOR one service.</b> qits-githost refuses a bearer whose audience names
 * qits-ci, so a single client could talk to one peer only. The client id is the same everywhere
 * (this service) and only {@code grant-options.client.audience} differs, which is also the one
 * setting the shipped defaults deliberately leave unset: it is environment-qualified
 * ({@code dev-qits-ci}) and an image every environment shares must not name a tier it may not be
 * running in.
 *
 * <p><b>The switch is the extension's own</b>, {@code quarkus.oidc-client.<peer>.client-enabled},
 * false in the shipped properties. There is no key of ours beside it — one switch cannot disagree
 * with itself. Off, this answers empty and the call goes out with the forward-auth headers alone.
 *
 * <p><b>Two of the six are for reads that are anonymous on qits-net today.</b> qits-artifacts'
 * registry routes and qits-platform-mirror's proxies take no credential in network, so those
 * clients exist for the day the edge's rule reaches the inside — turning one on is three
 * environment variables, not a code change.
 *
 * <p><b>A token this cannot mint is empty rather than an exception</b>, the orchestrator's stance:
 * the refusal that matters belongs to the call itself. An anonymous call to a guarded peer comes
 * back 401 and the row records the url and the status, which is more useful than a mint failure one
 * layer earlier.
 */
@ApplicationScoped
public class PeerTokens {

  private static final Logger LOG = Logger.getLogger(PeerTokens.class);

  /** The mint is not the call: this bounds the hop to idp, not the hop to the peer. */
  private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(5);

  @Inject
  @NamedOidcClient("projects")
  OidcClient projects;

  @Inject
  @NamedOidcClient("githost")
  OidcClient githost;

  @Inject
  @NamedOidcClient("ci")
  OidcClient ci;

  @Inject
  @NamedOidcClient("artifacts")
  OidcClient artifacts;

  @Inject
  @NamedOidcClient("mirror")
  OidcClient mirror;

  /**
   * The release door's, and the one whose ROLES matter as well as its audience: qits-workspaces
   * guards {@code /branches/release} with {@code qits:admin}, so this client's grant at
   * qits-platform-idp has to carry it. A client that mints happily and is then refused 403 is the
   * failure this note exists to make findable.
   */
  @Inject
  @NamedOidcClient("workspaces")
  OidcClient workspaces;

  /** Caches and refreshes each peer's token, so a scan of seventy repositories is not seventy
   * token requests. */
  private final Map<String, TokensHelper> helpers =
      Map.of(
          PeerTarget.Credential.PROJECTS, new TokensHelper(),
          PeerTarget.Credential.GITHOST, new TokensHelper(),
          PeerTarget.Credential.CI, new TokensHelper(),
          PeerTarget.Credential.ARTIFACTS, new TokensHelper(),
          PeerTarget.Credential.MIRROR, new TokensHelper(),
          PeerTarget.Credential.WORKSPACES, new TokensHelper());

  /** The bearer for one peer, or empty when its client is disabled or cannot mint. */
  public Optional<String> token(String credential) {
    if (!enabled(credential)) {
      return Optional.empty();
    }
    OidcClient client = client(credential);
    TokensHelper helper = helpers.get(credential);
    if (client == null || helper == null) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(
              helper.getTokens(client).await().atMost(TOKEN_TIMEOUT).getAccessToken())
          .filter(value -> !value.isBlank());
    } catch (RuntimeException e) {
      LOG.warnf("Could not get a machine token for %s: %s", credential, e.toString());
      return Optional.empty();
    }
  }

  /**
   * Read per call rather than injected as five booleans: the key name carries the peer, and one
   * lookup keeps this class from growing a field per peer twice over.
   */
  private boolean enabled(String credential) {
    return ConfigProvider.getConfig()
        .getOptionalValue("quarkus.oidc-client." + credential + ".client-enabled", Boolean.class)
        .orElse(false);
  }

  private OidcClient client(String credential) {
    return switch (credential) {
      case PeerTarget.Credential.PROJECTS -> projects;
      case PeerTarget.Credential.GITHOST -> githost;
      case PeerTarget.Credential.CI -> ci;
      case PeerTarget.Credential.ARTIFACTS -> artifacts;
      case PeerTarget.Credential.MIRROR -> mirror;
      case PeerTarget.Credential.WORKSPACES -> workspaces;
      default -> null;
    };
  }
}
