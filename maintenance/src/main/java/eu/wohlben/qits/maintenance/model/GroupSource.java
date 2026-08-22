package eu.wohlben.qits.maintenance.model;

/**
 * Where a group came from: the repository's own configuration, or the fallback.
 *
 * <p>It is shown rather than acted on — an operator looking at a repository whose every pin is in
 * one group needs to know whether that is a decision or an absence.
 */
public enum GroupSource {
  /** Declared in {@code .config/qits/maintenance.yml}. */
  CONFIG,

  /** The one catch-all group a repository with no configuration gets. */
  DEFAULT
}
