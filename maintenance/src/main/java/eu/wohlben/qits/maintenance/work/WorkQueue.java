package eu.wohlben.qits.maintenance.work;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jboss.logging.Logger;

/**
 * One thread, for every scan and every bump this service performs.
 *
 * <p><b>A sequence, not an interleaving.</b> A scan rewrites the inventory of every repository in
 * the catalog and a bump reads that inventory to decide what to send; two of them at once would let
 * a bump compute its payload from a repository half-rewritten by a scan. One thread also means a
 * scan's hundreds of reads never race a second scan's, so the git host and the registries see one
 * caller.
 *
 * <p><b>The route does not wait.</b> {@code POST /scans} and {@code POST …/bumps} answer 202 with
 * an id as soon as the decision is made; a scan is minutes of other services' reads and an HTTP
 * request is the wrong place to hold that.
 *
 * <p><b>A task never throws out of here.</b> A thrown exception on the worker would kill nothing —
 * a single-thread executor replaces its thread — but it would lose the sentence. Every task logs
 * its own failure and ends.
 */
@ApplicationScoped
public class WorkQueue {

  private static final Logger LOG = Logger.getLogger(WorkQueue.class);

  /**
   * The one worker. A daemon thread with a name, so a stuck scan is identifiable in a thread dump
   * and a JVM shutting down is not held open by a registry that will never answer.
   */
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "qits-maintenance-work");
            thread.setDaemon(true);
            return thread;
          });

  /** Queues one piece of work behind everything already queued. */
  public void submit(String what, Runnable task) {
    try {
      worker.execute(
          () -> {
            try {
              task.run();
            } catch (RuntimeException e) {
              LOG.errorf(e, "%s could not be completed", what);
            }
          });
    } catch (RejectedExecutionException e) {
      // The only way this happens is a shutdown in flight. Nothing to recover: the next boot's
      // schedule does the work, and the rows the task would have written are simply not written.
      LOG.warnf("%s was not queued: the service is shutting down", what);
    }
  }

  void onShutdown(@Observes ShutdownEvent event) {
    worker.shutdown();
    try {
      // Long enough for a call in flight to record its answer, short enough that a redeploy is not
      // held up by a peer that will never reply — every call has its own timeout anyway.
      if (!worker.awaitTermination(10, TimeUnit.SECONDS)) {
        worker.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      worker.shutdownNow();
    }
  }
}
