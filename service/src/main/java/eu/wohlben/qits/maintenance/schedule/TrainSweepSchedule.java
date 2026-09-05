package eu.wohlben.qits.maintenance.schedule;

import eu.wohlben.qits.maintenance.train.TrainSweep;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The clock in front of the config-pin sweep: the two release-train ends nothing announces.
 *
 * <p>An IMAGE release is adopted when a deployment configuration pins the new version, and a daemon
 * release is adopted when qits-ci hands out the new build. Neither fact is published, so neither can
 * be listened for — see {@link TrainSweep}, which holds every rule about what that means and is where
 * they are tested. This class is the trigger and nothing else.
 *
 * <p><b>Every ten minutes, at :10.</b> Far more often than the two scans and the SBOM sweep, because
 * a pin moving is a deployment somebody is watching rather than an index going stale; far less often
 * than the bump poller's fifteen seconds, because nobody is holding a page open on it. The minute is
 * staggered off every other timer this service runs (00:30 and 01:00 scans, 02:00 bump, :05 sbom), so
 * two of them never share a tick.
 *
 * <p><b>A no-op whenever nothing is owed</b>, which is most of the day: two indexed reads and a
 * return, with no call to either peer. That is the property that makes a ten-minute cron cheap.
 */
@ApplicationScoped
public class TrainSweepSchedule {

  private static final Logger LOG = Logger.getLogger(TrainSweepSchedule.class);

  @Inject TrainSweep sweep;

  @Scheduled(
      cron = "{qits.maintenance.train.sweep-cron}",
      timeZone = "{qits.maintenance.time-zone}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onSchedule() {
    try {
      sweep.sweep();
    } catch (RuntimeException e) {
      // A background chore's failure is a line in the log and a retry in ten minutes, never a dead
      // scheduler thread. Nothing here is lost by skipping a pass: the sweep decides on what it
      // reads, so the next one decides again from scratch.
      LOG.errorf(e, "The config-pin sweep failed; the next one retries.");
    }
  }
}
