package eu.wohlben.qits.maintenance.model;

import java.util.Optional;

/**
 * What kind of thing a repository is, as qits-projects classifies it.
 *
 * <p><b>This is a FOREIGN vocabulary, transcribed.</b> The enum lives in qits-projects, which owns
 * the value and answers it on {@code GET /projects/api/repositories} serialized by name. This copy
 * is the same transcription the {@code bus/} payload records are: binding to somebody else's class
 * would make their next refactor this service's compile error, and the wire is the contract either
 * way.
 *
 * <p><b>So the copy is expected to fall behind, and nothing here breaks when it does.</b> {@link
 * #of(String)} answers empty for a spelling this enum has not heard of, and the spelling itself is
 * kept verbatim on {@code mt_repository.archetype} — a column with no check constraint, for exactly
 * this reason. An archetype nobody here recognises costs a repository its place on a train; it does
 * not cost it its inventory row, and it never fails a scan.
 *
 * <p><b>Nothing here filters.</b> {@link #PROJECT} is what the wrapper repository answers, {@link
 * #FORK} and {@link #SERVICE_TEMPLATE} are real values too, and none of the three is a thing a
 * release train would place. Which archetypes a train accepts is the train layer's rule and is
 * written where that rule is — the catalog and the store carry all of them.
 */
public enum RepositoryArchetype {

  /** A deployable application with an HTTP surface. */
  SERVICE,

  /** A deployable with no HTTP surface of its own — it runs on a host and reports in. */
  DAEMON,

  /** A published library: a maven artifact or an npm package, consumed by other repositories. */
  LIBRARY,

  /** A single-page application, built and served by (or beside) a service. */
  FRONTEND,

  /** A command-line tool. */
  CLI,

  /** An OCI image published for other builds to use — a step image, a base image. */
  IMAGE,

  /**
   * The wrapper repository itself: the estate, not a member of it. It has submodules rather than a
   * deployable, and it is the archetype the home repository answers.
   */
  PROJECT,

  /** The skeleton a new service is generated from. Nothing deploys it. */
  SERVICE_TEMPLATE,

  /** Somebody else's repository, mirrored here. */
  FORK;

  /** The stored and served spelling — the enum's own name, as qits-projects serializes it. */
  public String wireName() {
    return name();
  }

  /**
   * The archetype for a wire spelling, or empty.
   *
   * <p><b>Lenient on purpose</b>, in all three directions: null and blank answer empty, the match
   * ignores case, and a value this enum does not carry answers empty rather than throwing. The
   * caller is reading another service's listing — see the class comment — and the only sound
   * response to a word it does not know is "then this is not one I can place".
   */
  public static Optional<RepositoryArchetype> of(String wireName) {
    if (wireName == null || wireName.isBlank()) {
      return Optional.empty();
    }
    String trimmed = wireName.trim();
    for (RepositoryArchetype value : values()) {
      if (value.name().equalsIgnoreCase(trimmed)) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }
}
