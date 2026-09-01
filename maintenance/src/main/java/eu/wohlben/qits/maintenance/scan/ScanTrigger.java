package eu.wohlben.qits.maintenance.scan;

/**
 * What asked for a scan, and the one thing it decides.
 *
 * <p><b>Only a SCHEDULED scan may bump.</b> A person pressing Scan is asking what is out of date; a
 * person who wants a branch presses Bump, on the group they mean. The clock is the caller with
 * standing instructions, which is what {@code qits.maintenance.bump.auto} states.
 */
public enum ScanTrigger {
  /** The button, or a machine posting to the route. */
  MANUAL,

  /** One of the two crons. */
  SCHEDULED,

  /**
   * A push to a repository's own main branch, off the bus.
   *
   * <p>It scans exactly that one repository, and — like MANUAL and unlike SCHEDULED — it never
   * bumps. What a push changes is a MANIFEST, so the honest answer to one is to re-read it; whether
   * the resulting pending set should become a branch is still the clock's standing instruction or a
   * person's press, and a bump fired from a push would put a branch on every repository somebody
   * touched during the day.
   *
   * <p>It is a new value and no migration: {@code mt_scan.trigger} is a {@code varchar} under no
   * check constraint, and the invariant lives where the writes are — {@code ScanService.request} is
   * the only writer and it takes this enum.
   */
  EVENT
}
