package eu.wohlben.qits.maintenance.model;

/**
 * The three package worlds a manifest can pin in.
 *
 * <p>It decides three things at once: which parser reads a manifest, which registry answers "what
 * is the latest", and which of the bump pipeline's two steps can edit the line. A fourth ecosystem
 * would arrive as a parser, a resolver and a step together.
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
  DOCKER;

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
