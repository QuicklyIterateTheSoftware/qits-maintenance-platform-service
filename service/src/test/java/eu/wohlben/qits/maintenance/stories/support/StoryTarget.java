package eu.wohlben.qits.maintenance.stories.support;

/**
 * The names and addresses every story in this catalogue shares — spelled once, so a diagram and the
 * assertion that pins it cannot disagree about what a thing is called.
 *
 * <p><b>A name here is a stable literal, never a run stamp.</b> {@link
 * eu.wohlben.qits.userflows.Labels} rewrites only what it can tell was generated — a UUID, a hex run
 * of 32 or more, a bare numeric path segment — so anything else in a label survives into the story's
 * {@code networkHash}. A fixture named after a timestamp would move that hash on every run, and the
 * only symptom is a hash that never settles.
 *
 * <p><b>A peer is named as the SERVICE a deployment dials</b>, not as a fixture. {@code
 * qits.maintenance.targets.githost-url} ships pointing at {@code http://qits-githost:8080}, so the
 * diagram says {@code qits-githost} and a reader sees the dependency the shipped configuration
 * declares rather than a loopback port this run happened to get.
 */
public final class StoryTarget {

  /**
   * How every diagram in this catalogue names the launched process, on both sides of an edge: the
   * {@code to} of everything a story sends here and the {@code from} of everything it sends out.
   */
  public static final String SERVICE = "qits-platform-maintenance";

  /**
   * How the diagram names the store. Not a host and not a datasource key — this service's own
   * postgres, reached over JDBC from inside the launched process, which is precisely the thing no
   * tap out here can see and the reason every edge to it is declared rather than observed.
   */
  public static final String STORE = "its own inventory store";

  // --- the five peers ----------------------------------------------------------------------------

  /** The catalog: every repository this service is responsible for. */
  public static final String PROJECTS = "qits-projects";

  /** Where the manifests are, read at one revision per repository. */
  public static final String GITHOST = "qits-githost";

  /** The one peer this service WRITES to — and it writes a trigger, never a commit. */
  public static final String CI = "qits-ci";

  /** The internal registries: maven, npm and OCI behind three path prefixes of one service. */
  public static final String ARTIFACTS = "qits-platform-artifacts";

  /** Maven Central and npmjs, cached — where an EXTERNAL pin's latest is asked. */
  public static final String MIRROR = "qits-platform-mirror";

  // --- the wire surface --------------------------------------------------------------------------

  /** The machine surface's root. Path-routed verbatim by the edge on every vhost. */
  public static final String API = "/maintenance/api";

  /** The landing read: every repository, its groups and what each has pending. */
  public static final String REPOSITORIES = API + "/repositories";

  /** The inventory read the other way round: one dependency, everyone who pins it. */
  public static final String DEPENDENCIES = API + "/dependencies";

  /** Re-read the manifests and refresh the latest versions. 202, never a wait. */
  public static final String SCANS = API + "/scans";

  /** The bump log — what was asked of qits-ci, and what came of it. */
  public static final String BUMPS = API + "/bumps";

  // --- the mount points the shipped configuration spells -----------------------------------------

  /** qits-artifacts' hosted maven repository, as {@code registries.maven-url} mounts it. */
  public static final String MAVEN_REGISTRY_PREFIX = "/artifacts/maven/maven";

  /** …its hosted npm repository. */
  public static final String NPM_REGISTRY_PREFIX = "/artifacts/npm/npm";

  /** …and its OCI registry, which is mounted at the Distribution spec's own root. */
  public static final String OCI_REGISTRY_PREFIX = "/v2";

  /** qits-platform-mirror's Maven Central pull-through. */
  public static final String MAVEN_MIRROR_PREFIX = "/artifacts/maven/central";

  /** …and its npmjs pull-through. */
  public static final String NPM_MIRROR_PREFIX = "/artifacts/npm/npmjs";

  // --- what a generated value becomes in a label -------------------------------------------------

  /**
   * The scrubbed marker a generated id becomes in a label — a scan id, a bump id. Authored here
   * rather than interpolated, because a story that put a real row id in a label would move its own
   * {@code networkHash} on every run, and the id is generated per run by definition.
   */
  public static final String ID = "{id}";

  /** The same, for a 40-hex commit sha: {@link eu.wohlben.qits.userflows.Labels} rewrites it. */
  public static final String DIGEST = "{digest}";

  private StoryTarget() {}
}
