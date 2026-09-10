package eu.wohlben.qits.maintenance.error;

/**
 * There is no dispatch window — a 404.
 *
 * <p><b>404 rather than a 200 carrying a null.</b> "Is tonight running" is a yes-or-no a caller
 * branches on, and an envelope whose every field is null is the shape that gets read as a window
 * whose times nobody filled in. The absent row IS the answer, and the status line says it.
 */
public class NoBumpWindowException extends MaintenanceException {

  public NoBumpWindowException() {
    super(404, "no bump dispatch window is open");
  }
}
