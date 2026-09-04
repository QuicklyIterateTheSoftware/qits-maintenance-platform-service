package eu.wohlben.qits.maintenance.error;

/**
 * The inventory holds no repository at all, so this service cannot say what is pinned — a 503.
 *
 * <p><b>It is a refusal rather than an empty answer, and the difference is somebody else's data.</b>
 * The pin source is read by the artifact GC as a keep-set: what it names is kept and what it does not
 * name may be deleted. A store that has never been filled — a fresh deployment before its first
 * scan, a restored database, a catalog read that has never once succeeded — would answer "nothing is
 * referenced", which reads exactly like "every internal library on the platform is unreferenced".
 * The consumer is fail-closed on an unanswered source, so refusing costs one GC run and answering
 * emptily costs the registry.
 *
 * <p><b>A scanned inventory in which some repositories are UNREACHABLE still answers.</b> Those rows
 * keep the pins their last successful scan read ({@code MaintenanceStore.markRepository}), so the
 * keep-set is stale rather than absent, and the status and {@code lastScanAt} of every row travel
 * with the answer for the consumer to judge. Only "no rows whatsoever" is the state no reader can
 * tell from an empty platform.
 *
 * <p>503 rather than 404: nothing is missing from the address, and the answer is expected to exist
 * shortly — the next scan fills the store, and a caller that retries gets it.
 */
public class EmptyInventoryException extends MaintenanceException {

  public EmptyInventoryException() {
    super(
        503,
        "the inventory holds no repository, so nothing can be said about what is pinned;"
            + " this service has not completed a scan yet");
  }
}
