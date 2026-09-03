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

  /**
   * There is nothing to scan, for one of two reasons, and the row's message says which.
   *
   * <ul>
   *   <li>the catalog names it and the git host has no such repository, or it has no {@code main};
   *   <li><b>the catalog no longer names it at all</b> — a rename or a removal, found by a full
   *       scan reconciling the inventory against the listing. The row is KEPT so the name still
   *       answers honestly and so {@code catalog_id} survives for the graph rows that translate
   *       through it, but its pins and its groups are gone: a repository the catalog dropped has no
   *       line anybody can edit and contributes no pending change.
   * </ul>
   *
   * <p>Either way the pins are gone, and the clock never bumps a row that is not {@link #OK}.
   */
  ABSENT,

  /** The git host could not be asked. The previous scan's pins are left standing — an unreachable
   * peer is not evidence that a repository stopped pinning anything. */
  UNREACHABLE,

  /** {@code .config/qits/maintenance.yml} does not parse. Nothing is bumped for this repository
   * until it does: a broken grouping would put changes on a branch nobody asked for. */
  CONFIG_ERROR
}
