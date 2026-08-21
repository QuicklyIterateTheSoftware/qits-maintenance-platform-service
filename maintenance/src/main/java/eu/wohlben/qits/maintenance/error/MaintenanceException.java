package eu.wohlben.qits.maintenance.error;

/**
 * A refusal this context can state in one sentence, carrying the HTTP status it means.
 *
 * <p>Framework-free on purpose: the domain jar has no JAX-RS on it, so the status travels as an int
 * and {@code service}'s mapper turns it into a response. The platform's envelope is one key,
 * {@code {"message": "..."}}.
 */
public abstract class MaintenanceException extends RuntimeException {

  private final int statusCode;

  protected MaintenanceException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
