package eu.wohlben.qits.maintenance.model;

/**
 * What asked for a bump. The wire spelling is upper case here, unlike a run trigger elsewhere on
 * the platform, because the plan pins the column's vocabulary as {@code SCHEDULED|MANUAL}.
 */
public enum BumpTrigger {
  /** A scan found pending changes in a group with no active bump, and {@code bump.auto} is on. */
  SCHEDULED,

  /** Somebody pressed the button, or a machine posted to the route. */
  MANUAL
}
