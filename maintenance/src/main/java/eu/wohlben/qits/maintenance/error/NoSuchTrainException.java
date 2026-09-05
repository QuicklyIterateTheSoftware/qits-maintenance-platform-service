package eu.wohlben.qits.maintenance.error;

/**
 * No such release train — a 404, whichever of the two ways it was asked for.
 *
 * <p>The id is a STRING for the reason {@link NoSuchBumpException}'s is: a malformed id and an
 * absent one are the same question from the caller's side, so the controller does not have to turn
 * a parse failure into a different answer.
 *
 * <p>The second constructor is the one the release cross-link reads. A release with no station is
 * <b>ordinary rather than broken</b> — a release published before this feature existed, or one this
 * service never heard about — and it has to be told apart from a train that exists and is empty,
 * which is a journey of length zero and answers 200.
 */
public class NoSuchTrainException extends MaintenanceException {

  public NoSuchTrainException(Object id) {
    super(404, "no release train '" + id + "'");
  }

  public NoSuchTrainException(String repository, String version) {
    super(404, "no release train for " + repository + " " + version);
  }
}
