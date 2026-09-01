package eu.wohlben.qits.maintenance.model;

/**
 * What asked for a bump. The wire spelling is upper case here, unlike a run trigger elsewhere on
 * the platform, because the plan pins the column's vocabulary as {@code SCHEDULED|MANUAL}.
 */
public enum BumpTrigger {
  /** The clock: {@code schedule/BumpSchedule} found a group pending with no active bump. */
  SCHEDULED,

  /** Somebody pressed the button, or a machine posted to the route. */
  MANUAL
}
