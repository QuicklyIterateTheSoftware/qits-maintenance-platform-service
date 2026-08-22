package eu.wohlben.qits.maintenance.error;

import java.util.UUID;

/**
 * A bump for that (repository, group) is already going — a 409.
 *
 * <p><b>One bump at a time per branch is a safety property, not a convenience.</b> The bump step
 * pushes ff-only onto one branch; two runs writing the same branch would make the second a non-ff
 * rejection at best, and at worst two commits computed from two different readings of the pins. The
 * message names the bump that holds the lock so the caller can go and read it.
 */
public class BumpAlreadyActiveException extends MaintenanceException {

  private final UUID activeBumpId;

  public BumpAlreadyActiveException(String repository, String group, UUID activeBumpId) {
    super(
        409,
        "a bump of " + repository + "/" + group + " is already active: " + activeBumpId);
    this.activeBumpId = activeBumpId;
  }

  public UUID activeBumpId() {
    return activeBumpId;
  }
}
