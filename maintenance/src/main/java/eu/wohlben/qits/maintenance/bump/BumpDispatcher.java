package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jboss.logging.Logger;

/**
 * ONE BUMP AT A TIME, ONLY WHEN qits-ci IS IDLE, AND ALWAYS FROM THE BOTTOM OF THE CHAIN.
 *
 * <h2>What it replaced</h2>
 *
 * <p>The nightly cron used to walk the inventory and call {@link BumpService#request} for every
 * eligible repository in one tight loop — no capacity check, no stagger, no cap and no ordering.
 * The first live run of it asked for 30 bumps at 02:00 and qits-ci was handed all thirty builds as
 * one wavefront. Backpressure existed but was purely reactive: a 503 from the trigger left the bump
 * REQUESTED and the poller re-sent it, which absorbs an overload after it has been created rather
 * than declining to create it.
 *
 * <p><b>So the cron stopped being the thing that dispatches and became the thing that opens a
 * window.</b> The two decisions were welded together and they answer to different facts: WHEN a
 * branch is welcome to arrive is a property of the night, and HOW MANY may be in flight is a
 * property of qits-ci at that instant. {@link #open} is the first; {@link #tick} is the second, and
 * it runs on the same short interval the bump poller does.
 *
 * <h2>The three gates, in the order they are asked</h2>
 *
 * <ol>
 *   <li><b>Is the window open</b> — and it closes by itself: when it expires, when the switches say
 *       no, and, the one that matters most, <b>as soon as nothing is owed</b>. A window that closed
 *       because the work is done is the ordinary ending.
 *   <li><b>Is anything of ours in flight</b> — {@code activeBumps}, which counts a person's press
 *       as well as the clock's. A bump that is REQUESTED or RUNNING is a build this service asked
 *       for and has not seen the end of.
 *   <li><b>Is qits-ci idle</b> — {@link CiClient#activeRuns()}, and an unreadable listing counts as
 *       BUSY. The one thing a gate must never do is treat "I could not ask" as "nothing is going".
 * </ol>
 *
 * <p><b>Both counts are compared against the same knob</b>, {@code
 * qits.maintenance.bump.dispatch.max-in-flight}. At its default of 1 that reads exactly as the
 * request: an empty CI queue and no bump of ours outstanding. Above 1 it is a depth rather than a
 * switch, and the two halves stay consistent — three allowed in flight means three runs may be
 * sitting in qits-ci's queue.
 *
 * <h2>Recomputed every tick, never a night's frozen plan</h2>
 *
 * <p>{@link PendingChanges} is computed on every read, so recomputing the candidate list costs
 * nothing and buys the whole point of the ordering: once the bottom bump releases and the next scan
 * has read it, its consumers' pending sets name the version that was just cut. A plan frozen at
 * 02:00 would hand every consumer the pin it was already going to get.
 *
 * <p><b>The window lives in memory and a restart drops it.</b> That is deliberate and it is the
 * cheap correct answer: a process that is down at 02:00 misses the night today too, the state is
 * one instant that is worthless an hour later, and a table for it would be a schema change to
 * remember something the next cron re-derives. What a restart cannot lose is a bump — those are
 * rows, and {@code BumpPollSchedule} picks up whatever was in flight.
 */
@ApplicationScoped
public class BumpDispatcher {

  private static final Logger LOG = Logger.getLogger(BumpDispatcher.class);

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject CiClient ci;

  @Inject BumpService bumps;

  @Inject ArtifactGraph artifacts;

  /** When the open window ends; null when there is no window and nothing is dispatched. */
  private final AtomicReference<Instant> windowCloses = new AtomicReference<>();

  /**
   * Repositories whose request was REFUSED this window, and which are therefore not asked for
   * again until the next one.
   *
   * <p>Without it a candidate that cannot be requested at all — a store that will not answer for
   * that row, a group that vanished between the read and the call — is picked again every fifteen
   * seconds for the length of the window, and, being the bottom of the chain, it holds up
   * everything behind it. One WARN and move on is what the loop-and-fire did too.
   */
  private final Set<String> refused = new LinkedHashSet<>();

  /** Opens the dispatch window. The cron's whole job. */
  public void open(Instant now) {
    Instant closes = now.plus(config.bumpWindow());
    synchronized (refused) {
      refused.clear();
    }
    windowCloses.set(closes);
    LOG.infof(
        "The bump dispatch window is open until %s; one bump goes at a time, from the bottom of the"
            + " chain, whenever qits-ci is idle.",
        closes);
  }

  /** Closes it, saying why. Idempotent — a window that is already shut logs nothing. */
  public void close(String why) {
    if (windowCloses.getAndSet(null) != null) {
      LOG.infof("The bump dispatch window is closed: %s.", why);
    }
  }

  /** Whether anything would be dispatched at all right now. */
  public boolean windowOpen(Instant now) {
    Instant closes = windowCloses.get();
    return closes != null && now.isBefore(closes);
  }

  /**
   * One dispatch decision.
   *
   * @return the bump that was asked for, or empty — which is every other outcome, and none of them
   *     is a failure
   */
  public Optional<UUID> tick() {
    Instant now = Instant.now();
    if (windowCloses.get() == null) {
      return Optional.empty();
    }
    if (!windowOpen(now)) {
      close("it ended with work still owed; the next schedule opens a new one");
      return Optional.empty();
    }
    if (!config.bumpEnabled()) {
      close("qits.maintenance.bump.enabled is false");
      return Optional.empty();
    }
    if (!config.bumpInternalAuto()) {
      close("qits.maintenance.bump.internal.auto is false");
      return Optional.empty();
    }

    // THE IN-FLIGHT COUNT IS ASKED BEFORE THE CANDIDATES, and the order is what keeps the window
    // from shutting on its own work: the repository being bumped right now is not a candidate — an
    // active bump is a skip — so a check the other way round would read "nothing is owed" while the
    // night's last bump is still running.
    int allowed = config.bumpMaxInFlight();
    int inFlight = store.activeBumps().size();
    if (inFlight >= allowed) {
      LOG.debugf("%d bump(s) are in flight and %d are allowed; nothing is dispatched.", inFlight, allowed);
      return Optional.empty();
    }

    List<BumpOrder.Candidate> candidates = candidates();
    if (candidates.isEmpty()) {
      close("nothing is owed a bump");
      return Optional.empty();
    }

    CiClient.QueueState queue = ci.activeRuns();
    if (!queue.readable()) {
      // Unreadable is BUSY. Dispatching blind here is precisely the wavefront this gate exists for.
      LOG.debugf("qits-ci's queue could not be read (%s); nothing is dispatched.", queue.error());
      return Optional.empty();
    }
    if (queue.active() >= allowed) {
      LOG.debugf(
          "qits-ci holds %d active run(s) and %d are allowed; nothing is dispatched.",
          queue.active(), Integer.valueOf(allowed));
      return Optional.empty();
    }

    Optional<BumpOrder.Pick> pick = BumpOrder.next(candidates, artifacts.producers());
    if (pick.isEmpty()) {
      return Optional.empty();
    }
    return dispatch(pick.get(), candidates.size());
  }

  private Optional<UUID> dispatch(BumpOrder.Pick pick, int owed) {
    BumpOrder.Candidate candidate = pick.candidate();
    if (pick.cycleBroken()) {
      // Every candidate waits on another candidate. The graph is not guaranteed acyclic and this
      // must not become a night in which nothing moves.
      LOG.warnf(
          "Every one of the %d owed bumps waits on another; %s is dispatched anyway (it waits on"
              + " %s), and the next tick asks again.",
          owed, candidate.repository(), pick.blockedBy());
    }
    try {
      UUID id = bumps.request(candidate.repository(), candidate.group(), BumpTrigger.SCHEDULED);
      LOG.infof(
          "Dispatched the scheduled bump %s of %s/%s (%d changes); %d repositor(ies) are still"
              + " owed one.",
          id, candidate.repository(), candidate.group(), candidate.changes().size(), owed - 1);
      return Optional.of(id);
    } catch (RuntimeException e) {
      synchronized (refused) {
        refused.add(candidate.repository());
      }
      LOG.warnf(
          "Could not dispatch the scheduled bump of %s/%s: %s; it is left for the next window.",
          candidate.repository(), candidate.group(), e.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Every repository owed an INTERNAL bump right now, in the inventory's listing order.
   *
   * <p>The filters are the ones the nightly loop always had — status OK, the group exists,
   * something is pending, no writer on that branch — and they are applied here rather than there
   * because they are now a question asked every fifteen seconds instead of once a night. See {@code
   * BumpSchedule} for why each of them is a skip rather than a failure.
   */
  public List<BumpOrder.Candidate> candidates() {
    String group = GroupConfig.DEFAULT_GROUP;
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    List<BumpOrder.Candidate> candidates = new ArrayList<>();
    for (MtRepository row : store.repositories()) {
      if (!RepositoryStatus.OK.name().equals(row.status)) {
        continue;
      }
      synchronized (refused) {
        if (refused.contains(row.name)) {
          continue;
        }
      }
      List<MtGroup> groups = store.groups(row.name);
      if (groups.stream().noneMatch(candidate -> candidate.name.equals(group))) {
        continue;
      }
      List<MtPin> pins = store.pins(row.name);
      List<Change> changes = PendingChanges.forGroup(pins, latest, groups, group);
      if (changes.isEmpty()) {
        continue;
      }
      if (store.activeBump(row.name, group).isPresent()) {
        continue;
      }
      candidates.add(new BumpOrder.Candidate(row.name, group, changes));
    }
    return List.copyOf(candidates);
  }
}
