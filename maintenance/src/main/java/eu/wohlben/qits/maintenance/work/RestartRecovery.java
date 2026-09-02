package eu.wohlben.qits.maintenance.work;

import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.SbomIngestService;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Instant;
import org.jboss.logging.Logger;

/**
 * What a restart does to the work that was in flight when the last process died.
 *
 * <p><b>Scans are FAILED and bumps are RESUMED, and the difference is where the work lives.</b>
 *
 * <ul>
 *   <li>A SCAN's work is entirely in the process: reads it made, rows it wrote, a position in a
 *       loop nothing recorded. A successor knows none of it, so the row is closed FAILED with a
 *       sentence saying why. Nothing is lost that a rescan does not recover, and the next schedule
 *       is minutes away.
 *   <li>A BUMP's work is qits-ci's. The run outlived this service and its answer is still there to
 *       read, so failing the row would throw away a branch that may already have been pushed. The
 *       ordinary sweep is the recovery: it re-dispatches a REQUESTED bump under the same event id —
 *       which is what makes the retry record no second run — and polls a RUNNING one to its end.
 *       This class only starts the first sweep, so the recovery does not wait for the first tick.
 *   <li>An SBOM that was never read is simply READ. A PENDING {@code mt_artifact} row is a fetch
 *       that was queued and never ran — nothing in-process was lost and nothing is running
 *       elsewhere, because the work is one idempotent read of an immutable document. So it is
 *       re-queued rather than failed or followed.
 * </ul>
 *
 * <p><b>It never stops the boot.</b> A store that will not answer at startup is a readiness
 * question the deployer already health-gates; refusing to start would turn one slow postgres into a
 * service that is not there at all.
 */
@ApplicationScoped
public class RestartRecovery {

  /** What an interrupted scan's row says afterwards. */
  public static final String INTERRUPTED = "interrupted by restart";

  private static final Logger LOG = Logger.getLogger(RestartRecovery.class);

  @Inject MaintenanceStore store;

  @Inject BumpService bumps;

  @Inject SbomIngestService sboms;

  void onStart(@Observes StartupEvent event) {
    try {
      long closed = store.failInterruptedScans(INTERRUPTED, Instant.now());
      if (closed > 0) {
        LOG.warnf("Closed %d scan(s) left open by a previous process.", closed);
      }
    } catch (RuntimeException e) {
      LOG.error("The scans left open by a previous process could not be closed.", e);
    }
    try {
      // Queued, not run: the sweep only puts work on the single worker thread, so this returns at
      // once and the boot is not held by a peer that will never answer.
      bumps.sweep();
    } catch (RuntimeException e) {
      LOG.error("The bumps left open by a previous process could not be resumed.", e);
    }
    try {
      // AND THE SBOMS, WHICH ARE A THIRD SHAPE AGAIN. A PENDING artifact row is a fetch that was
      // queued and never ran, and there is nothing in-process to lose and nothing running elsewhere
      // to follow: the work is one idempotent read of an immutable document. So a successor simply
      // does it — no row is failed, and none is left waiting for the hourly sweep.
      sboms.sweep();
    } catch (RuntimeException e) {
      LOG.error("The sboms a previous process had not read could not be re-queued.", e);
    }
  }
}
