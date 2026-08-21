package eu.wohlben.qits.maintenance.model;

/**
 * What the last scan of one repository found.
 *
 * <p>Everything except {@link #OK} carries a sentence in {@code mt_repository.message}, because the
 * UI shows the reason rather than the word.
 */
public enum RepositoryStatus {
  /** Scanned; the pins in {@code mt_pin} are this repository's, at {@code head_sha}. */
  OK,

  /** The catalog names it but the git host has no such repository, or it has no {@code main}. */
  ABSENT,

  /** The git host could not be asked. The previous scan's pins are left standing — an unreachable
   * peer is not evidence that a repository stopped pinning anything. */
  UNREACHABLE,

  /** {@code .config/qits/maintenance.yml} does not parse. Nothing is bumped for this repository
   * until it does: a broken grouping would put changes on a branch nobody asked for. */
  CONFIG_ERROR
}
