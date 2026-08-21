package eu.wohlben.qits.maintenance.error;

/**
 * No bump with that id — a 404.
 *
 * <p>The id is a STRING because a malformed one and an absent one are the same question from the
 * caller's side.
 */
public class NoSuchBumpException extends MaintenanceException {

  public NoSuchBumpException(Object id) {
    super(404, "no bump '" + id + "'");
  }
}
