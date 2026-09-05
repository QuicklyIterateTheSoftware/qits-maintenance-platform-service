package eu.wohlben.qits.maintenance.model;

/**
 * How far one expected adopter has got with the version its train carries.
 *
 * <p><b>Two steps, not one, because taking a version and shipping it are different facts.</b> A
 * repository whose pom property moved has ADOPTED — somebody can see the new version in its tree —
 * and the estate is still not carrying it: nothing is deployed, nothing downstream can pin it, and
 * the change can still be reverted. LANDED is the adoption released and integrated, which is the
 * only state that lets the train say a consumer is done.
 *
 * <p><b>Nothing here moves at spawn.</b> Every node is born PENDING; the evaluation that reads a
 * pin, a configuration or a release and decides otherwise is its own concern and writes through
 * these values.
 */
public enum TrainNodeState {

  /** The consumer has not taken the version. Every node is born here. */
  PENDING,

  /** The consumer's tree carries the version — a moved pin, a config change — and has not shipped it. */
  ADOPTED,

  /** The adoption is released and integrated. This node owes the train nothing more. */
  LANDED;

  /** Whether the train is still owed something by this node. */
  public boolean owed() {
    return this != LANDED;
  }

  /** The state for a stored value, defaulting to {@link #PENDING} for a word this build lacks. */
  public static TrainNodeState of(String value) {
    if (value == null) {
      return PENDING;
    }
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return PENDING;
    }
  }
}
