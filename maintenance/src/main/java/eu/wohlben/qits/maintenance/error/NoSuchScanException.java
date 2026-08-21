package eu.wohlben.qits.maintenance.error;

/**
 * No scan with that id — a 404.
 *
 * <p>The id is a STRING because a malformed one and an absent one are the same question from the
 * caller's side.
 */
public class NoSuchScanException extends MaintenanceException {

  public NoSuchScanException(Object id) {
    super(404, "no scan '" + id + "'");
  }
}
