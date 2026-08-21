package eu.wohlben.qits.maintenance.model;

/**
 * Whether this platform publishes the dependency or somebody else does.
 *
 * <p><b>It is a NAME rule, configured</b> — maven groups, npm scopes, image prefixes — and not a
 * lookup: asking a registry whether it holds a package would make every scan a round trip per
 * dependency, and a registry that is briefly down would reclassify half the inventory.
 *
 * <p>It decides which registry is asked for the latest version and which of the two scan schedules
 * refreshes it: an internal release lands hourly-ish, an external one is a daily question.
 */
public enum PinKind {
  /** Published by this platform, into qits-artifacts. */
  INTERNAL,

  /** Everybody else's, read through qits-platform-mirror. */
  EXTERNAL
}
