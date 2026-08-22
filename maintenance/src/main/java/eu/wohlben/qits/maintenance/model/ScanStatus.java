package eu.wohlben.qits.maintenance.model;

/**
 * One scan's life.
 *
 * <p><b>FAILED means the scan did nothing</b> — the catalog could not be read, or it matched no
 * repository. A scan that read seventy repositories and could not reach one SUCCEEDED; that one
 * repository's row says UNREACHABLE, which is where the fact belongs.
 */
public enum ScanStatus {
  /** Queued behind whatever the worker is doing. */
  REQUESTED,

  /** The worker picked it up. */
  RUNNING,

  SUCCEEDED,

  FAILED;

  public boolean terminal() {
    return this == SUCCEEDED || this == FAILED;
  }
}
