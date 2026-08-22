package eu.wohlben.qits.maintenance.error;

/**
 * The caller asked for something this service cannot read — a 400.
 *
 * <p>An unknown scan scope is the whole of what raises it today: the vocabulary is
 * {@code INTERNAL|EXTERNAL|ALL} and a fourth word is a typo, not an empty result.
 */
public class BadRequestException extends MaintenanceException {

  public BadRequestException(String message) {
    super(400, message);
  }
}
