package eu.wohlben.qits.maintenance.error;

/**
 * That repository has no such group — a 404.
 *
 * <p>A group exists because a scan found it, in the repository's own configuration or as the
 * fallback. Bumping a group nobody declared would push a branch named after a typo.
 */
public class NoSuchGroupException extends MaintenanceException {

  public NoSuchGroupException(String repository, String group) {
    super(404, "repository '" + repository + "' has no group '" + group + "'");
  }
}
