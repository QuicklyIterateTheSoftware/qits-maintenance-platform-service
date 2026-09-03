package eu.wohlben.qits.maintenance.peer;

/**
 * Every address this service reads or writes, and the credential each one takes.
 *
 * <p><b>Nine targets, five credentials.</b> A target is an ADDRESS — a configured base url a path
 * is appended to — while a credential is an oidc client, and a token is cut for one SERVICE. The
 * three registry targets on qits-artifacts share one client because they are one service behind
 * three path prefixes; splitting them would be three tokens for one audience.
 *
 * <p>Constants rather than an enum for the credential name, because the same string is two things:
 * the middle of {@code quarkus.oidc-client.<name>.*} and the key {@link PeerTokens} switches on.
 */
public enum PeerTarget {

  /**
   * qits-projects — the catalog, the name-addressed coordinate every other read uses, and since the
   * release door was retired the RELEASE ASK as well.
   *
   * <p>A bump that ends SUCCEEDED opens a release request here on the branch it just pushed:
   * {@code POST /projects/api/repositories/<repoId>/release-requests}, addressed by the catalog row's
   * own id. See {@link eu.wohlben.qits.maintenance.bump.ReleaseRequestClient}. It is the same
   * credential every catalog read already mints — the route admits {@code qits:system}, which is what
   * {@link PeerClient} presents — so the write cost this service nothing a read did not already have.
   */
  PROJECTS("qits.maintenance.targets.projects-url", Credential.PROJECTS),

  /** qits-githost — the manifests, read at one revision per repository. */
  GITHOST("qits.maintenance.targets.githost-url", Credential.GITHOST),

  /** qits-ci — the trigger that applies a bump, and the run it names. */
  CI("qits.maintenance.targets.ci-url", Credential.CI),

  /** qits-artifacts' hosted maven repository: {@code maven-metadata.xml} for internal artifacts. */
  MAVEN_REGISTRY("qits.maintenance.registries.maven-url", Credential.ARTIFACTS),

  /** qits-artifacts' hosted npm repository: the packument's {@code dist-tags.latest}. */
  NPM_REGISTRY("qits.maintenance.registries.npm-url", Credential.ARTIFACTS),

  /** qits-artifacts' OCI registry: {@code /<name>/tags/list}. */
  OCI_REGISTRY("qits.maintenance.registries.oci-url", Credential.ARTIFACTS),

  /**
   * qits-artifacts' SBOM store: {@code /artifacts/sboms/<type>/<name>/-/<version>}.
   *
   * <p><b>A fourth address on the same service, and it carries no path prefix.</b> The three
   * registry keys above each name a MOUNT — {@code /artifacts/maven/maven} is one repository row
   * and moving it is a deployment's decision — while the SBOM route is qits-artifacts' own API and
   * its whole path belongs to the caller. So the key is a bare host and the prefix is in the code.
   */
  ARTIFACTS_SBOM("qits.maintenance.targets.artifacts-url", Credential.ARTIFACTS),

  /** qits-platform-mirror's Maven Central pull-through. */
  MAVEN_MIRROR("qits.maintenance.mirror.maven-url", Credential.MIRROR),

  /** qits-platform-mirror's npmjs pull-through. */
  NPM_MIRROR("qits.maintenance.mirror.npm-url", Credential.MIRROR);

  /** The five oidc client names — one per SERVICE, because a token is cut for one service. */
  public static final class Credential {
    public static final String PROJECTS = "projects";
    public static final String GITHOST = "githost";
    public static final String CI = "ci";
    public static final String ARTIFACTS = "artifacts";
    public static final String MIRROR = "mirror";

    private Credential() {}
  }

  private final String urlKey;
  private final String credential;

  PeerTarget(String urlKey, String credential) {
    this.urlKey = urlKey;
    this.credential = credential;
  }

  /** The config key holding this target's base url. */
  public String urlKey() {
    return urlKey;
  }

  /** The oidc client that mints for the service behind this address. */
  public String credential() {
    return credential;
  }
}
