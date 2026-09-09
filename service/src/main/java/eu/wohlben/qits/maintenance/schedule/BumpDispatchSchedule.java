package eu.wohlben.qits.maintenance.schedule;

import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * What hands the night's bumps out, one at a time.
 *
 * <p><b>The tick is short and the decision is small</b>: while {@link BumpSchedule}'s window is
 * open, ask whether qits-ci is idle and, if it is, send the deepest owed bump. Everything about why
 * — the gates, the ordering, the cycle rule — is {@link BumpDispatcher}'s; what lives here is the
 * clock and the one guarantee a schedule owes: a failure is a line in the log and a retry fifteen
 * seconds later, never a dead scheduler thread.
 *
 * <p><b>It shares {@code bump.poll-interval} with {@link BumpPollSchedule} on purpose.</b> That
 * interval is already sized as "far below the length of any pipeline and far above the cost of the
 * read", which is exactly what this needs: the gate is one GET against a listing bounded by the
 * worker pool, and the thing it is waiting for is a build finishing. A second knob would be a
 * second number to keep consistent with the first for no gain.
 *
 * <p><b>Its own timer rather than a line inside the poll sweep</b>, because the two are different
 * jobs with different failure modes — the sweep moves work this service already started, and this
 * decides whether to start any — and {@code SKIP} on one must not delay the other.
 *
 * <p><b>Outside a window this costs nothing at all.</b> The dispatcher answers on a null window
 * before it reads anything, so for twenty-three hours of the day this is a field read.
 */
@ApplicationScoped
public class BumpDispatchSchedule {

  private static final Logger LOG = Logger.getLogger(BumpDispatchSchedule.class);

  @Inject BumpDispatcher dispatcher;

  @Scheduled(
      every = "{qits.maintenance.bump.poll-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onSchedule() {
    try {
      dispatcher.tick();
    } catch (RuntimeException e) {
      LOG.errorf(e, "The bump dispatch tick failed; the next one retries.");
    }
  }
}
