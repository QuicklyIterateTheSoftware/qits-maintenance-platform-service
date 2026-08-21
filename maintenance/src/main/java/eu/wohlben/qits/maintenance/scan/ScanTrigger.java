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
  SCHEDULED
}
