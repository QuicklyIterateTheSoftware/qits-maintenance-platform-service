package eu.wohlben.qits.maintenance.error;

/**
 * The inventory holds no repository of that name — a 404.
 *
 * <p>"Never scanned" and "not in the catalog" are the same answer here on purpose: a repository
 * with no row has nothing to show either way, and inventing a difference would mean answering for
 * qits-projects.
 */
public class NoSuchRepositoryException extends MaintenanceException {

  public NoSuchRepositoryException(String name) {
    super(404, "no repository '" + name + "' in the inventory");
  }
}
