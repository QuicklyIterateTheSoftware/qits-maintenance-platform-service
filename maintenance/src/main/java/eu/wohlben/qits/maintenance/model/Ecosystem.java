package eu.wohlben.qits.maintenance.model;

/**
 * The four worlds a manifest can pin in.
 *
 * <p>It decides three things at once: which parser reads a manifest, which registry answers "what
 * is the latest", and which of the bump pipeline's two steps can edit the line. A new ecosystem
 * arrives as a parser, a resolver and a step together.
 *
 * <p>The wire spelling is lower case — it is a label the UI groups by, not a constant a client
 * branches on.
 */
public enum Ecosystem {
  /** {@code pom.xml}, named {@code groupId:artifactId}. */
  MAVEN,

  /** {@code package.json} plus its lock, named {@code @scope/name} or {@code name}. */
  NPM,

  /** {@code Dockerfile} {@code FROM} lines, named by the image without its tag. */
  DOCKER,

  /**
   * A {@code .gitmodules} submodule, named by the repository its url points at.
   *
   * <p><b>The one ecosystem with no registry.</b> Nothing publishes a gitlink, so no lookup can ask
   * what its newest version is — the fact arrives as a {@code SCMRelease} off the bus instead, and
   * the daily scan neither refreshes it nor clears it. It is also the one whose PIN is not a
   * version: a gitlink records a commit sha, which nothing orders, so pending is a difference
   * rather than a comparison. See {@code pending/PendingChanges} and {@code latest/GitlinkSha}.
   */
  GITLINK;

  /** The stored and served spelling. */
  public String wireName() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }

  /** The ecosystem for a wire name, or empty. */
  public static java.util.Optional<Ecosystem> of(String wireName) {
    for (Ecosystem value : values()) {
      if (value.wireName().equalsIgnoreCase(wireName)) {
        return java.util.Optional.of(value);
      }
    }
    return java.util.Optional.empty();
  }
}
