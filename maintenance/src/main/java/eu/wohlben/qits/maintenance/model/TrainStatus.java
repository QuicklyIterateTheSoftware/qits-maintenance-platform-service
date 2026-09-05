package eu.wohlben.qits.maintenance.model;

/**
 * Where one release's train is in its journey.
 *
 * <p><b>There is no FAILED, and that is a decision rather than a gap.</b> A train records a journey
 * across repositories nobody controls centrally, and an adoption that has not happened yet is
 * indistinguishable from one that never will — there is no deadline anywhere on this platform after
 * which a repository is declared to have refused a version. A FAILED train would be this service
 * inventing a verdict it has no evidence for. What ends a train is arrival ({@link #COMPLETED}) or
 * irrelevance ({@link #SUPERSEDED}).
 */
public enum TrainStatus {

  /** Adopters are still owed. The ordinary state, and the only one a spawn can open into. */
  OPEN,

  /**
   * Every node landed — or there were none to land, which is the same fact about a release nobody
   * was expected to adopt. A degenerate train is COMPLETED at creation.
   */
  COMPLETED,

  /**
   * A newer release of the same repository is out, so whatever this train is still owed the estate
   * should be adopting the newer version instead.
   *
   * <p><b>The nodes keep evaluating.</b> A superseded train is not abandoned: a repository that
   * takes the version this train carried has still adopted it, and the row should say so. What
   * supersession changes is what a person is shown at the top of a listing, not whether the
   * evidence is read.
   */
  SUPERSEDED;

  /** Whether this train is still owed anything. */
  public boolean open() {
    return this == OPEN;
  }

  /**
   * The status for a stored or wire value, defaulting to {@link #OPEN} for a word this build does
   * not carry — the lenient reading every status column in this schema gets, because a row keeps
   * the word it was written with and a newer build's vocabulary must not make an older row
   * unreadable.
   */
  public static TrainStatus of(String value) {
    if (value == null) {
      return OPEN;
    }
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return OPEN;
    }
  }
}
