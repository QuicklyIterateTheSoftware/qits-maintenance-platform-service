package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtBumpWindow;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.model.BumpStatus;
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
 * <h2>Pending is read off main, so a bumped repository stays owed — and is HELD, not re-sent</h2>
 *
 * <p><b>The one live failure this gate had was re-dispatching the same repository every tick.</b> A
 * bump writes the {@code maintenance/dependencies} branch and opens a release request; {@link
 * PendingChanges} reads the pins on <i>main</i>, and main does not move until that release lands and
 * the next scan re-reads it. The bump itself has ended, so {@code activeBump} is gone, so the very
 * next tick saw the repository owed again and sent it — a second CI run that could only come back
 * NOTHING_TO_DO, then a third, for as long as the window was open.
 *
 * <p>So a candidate is HELD when <b>the newest bump row for that group ended without failing</b> —
 * SUCCEEDED or NOTHING_TO_DO — <b>and recorded the same set of changes it would carry now</b>. Same
 * set, order-insensitive, compared on (ecosystem, name, from, to): the manifest path and the
 * location are how an edit is applied, not what it is. A held candidate is not dispatched, stays in
 * the owed set so {@link BumpOrder} keeps its consumers waiting, and keeps the window open — a night
 * whose whole remaining chain is waiting on releases must not end early.
 *
 * <p><b>Nothing unwinds a hold, because nothing has to.</b> The release lands, a scan re-reads main,
 * the pending set empties and the repository stops being a candidate at all — there is no expiry, no
 * timer and no state to reconcile. If the release never lands, the six-hour window expiring is the
 * backstop, and the next night starts from whatever main actually says. A FAILED bump holds nothing:
 * a failure has to be retryable, and a changed pending set holds nothing either — a new upstream
 * release arrived, and that is a different bump.
 *
 * <h2>The window is a ROW, because this service redeploys itself in the middle of one</h2>
 *
 * <p>It was first an {@code AtomicReference<Instant>} here, with a paragraph arguing that losing it
 * to a restart was the cheap correct answer — a process down at 02:00 misses the night today too.
 * <b>That was wrong, and wrong for a reason particular to this service: qits-maintenance is one of
 * the repositories qits-maintenance bumps.</b> Measured live on 2026-09-10 — nineteen bumps
 * dispatched one at a time from 06:32, then the bump of {@code qits-maintenance-platform-service}
 * itself succeeded at 08:01, its release deployed at 08:11, the container was replaced, and nothing
 * was dispatched again: eleven repositories owed, four hours of window left, and no cron until the
 * next night. A restart mid-window is not this design's rare accident, it is its ordinary outcome,
 * since a successful bump here becomes a release and a release becomes a redeploy of this
 * container.
 *
 * <p>So the window is {@code mt_bump_window} — one row, {@link MtBumpWindow#INTERNAL}, deleted when
 * it closes. A restart resumes the night where it was. What it does NOT do is make the window
 * eternal: {@code closesAt} is compared with the clock exactly as the field was, so a window whose
 * service was down for its whole six hours comes back already over and closes on its first tick.
 * The cost is one primary-key read every fifteen seconds; see {@link
 * eu.wohlben.qits.maintenance.persistence.MaintenanceStore#bumpWindow()}.
 *
 * <h2>A hold has to ask what became of the release it is waiting for</h2>
 *
 * <p>The hold above rests on one assumption — that the release the bump opened is on its way — and
 * <b>the fourth live failure of this gate was that assumption being false and nothing noticing.</b>
 * Measured on 2026-09-10: of the night's twenty bumps, nineteen released; the twentieth,
 * {@code qits-deployments-platform-service}, had its release request REJECTED at 07:14 because its
 * gating build does not compile. Four hours later the window was still open, qits-ci was idle, that
 * one repository was still the only thing owed, nothing had been dispatched since 10:54, and no line
 * anywhere said why. A dead release and a release in flight were the same state to this service, and
 * a dead one holds for ever: nothing unwinds a hold except main moving, and main was never going to
 * move.
 *
 * <p>So a held candidate's release request is read — {@link ReleaseRequestClient#state} — and there
 * are three answers, not two:
 *
 * <ul>
 *   <li><b>PENDING, READY, RELEASED</b> — HELD, exactly as before. Something is coming.
 *   <li><b>REJECTED, FAILED, CONFLICTED, WITHDRAWN</b> — <b>STALLED</b>: dropped from the candidate
 *       list altogether. It stops blocking its consumers (they are waiting on a version that is not
 *       going to be cut, and building against the pin that exists is the honest answer), and it
 *       stops keeping the window open (a night must not stay open for work that cannot be done).
 *       It is reported, with qits-projects' own sentence, on {@code GET /bumps/window}.
 *   <li><b>Unreadable</b> — HELD. A peer that could not be asked is never evidence, which is the
 *       same reading the CI gate takes of an unreadable queue.
 * </ul>
 *
 * <p><b>Stalled is re-asked every tick and never stored as a verdict</b>, because qits-projects
 * re-arms REJECTED, FAILED and CONFLICTED back to PENDING on the next merged sha — a push to the
 * branch, a sibling's release, a pending tag reaching main. A request that comes back to life is
 * simply held again on the tick after it does, with nothing to unwind. What IS written to the row is
 * the observation ({@code mt_bump.release_state}, V11), so a bump standing since the morning explains
 * itself in a listing.
 *
 * <p><b>A stalled candidate is never re-dispatched by the clock, and that is deliberate.</b> Its
 * branch already carries the change, so a fresh bump could only come back NOTHING_TO_DO; what the
 * repository needs is a person, or the upstream release that re-arms the fold. Pressing Bump by hand
 * goes through {@link BumpService#request} and is not gated here at all.
 */
@ApplicationScoped
public class BumpDispatcher {

  private static final Logger LOG = Logger.getLogger(BumpDispatcher.class);

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject CiClient ci;

  @Inject BumpService bumps;

  @Inject ReleaseRequestClient releases;

  @Inject ArtifactGraph artifacts;

  /**
   * Repositories whose request was REFUSED this window, and which are therefore not asked for
   * again until the next one.
   *
   * <p>Without it a candidate that cannot be requested at all — a store that will not answer for
   * that row, a group that vanished between the read and the call — is picked again every fifteen
   * seconds for the length of the window, and, being the bottom of the chain, it holds up
   * everything behind it. One WARN and move on is what the loop-and-fire did too.
   *
   * <p><b>This one stays in memory even though the window no longer does</b>, and a restart
   * therefore forgives it. That is the right way round: a refusal is a guess that something is
   * wrong with one repository right now, the process it was a guess about is gone, and the cost of
   * being wrong is one CI run rather than a night that stops. The window is the opposite — losing
   * it costs the whole remaining chain — which is exactly why only one of the two is a row.
   */
  private final Set<String> refused = new LinkedHashSet<>();

  /**
   * What qits-projects last said about a release request, and when it said it.
   *
   * <p>The ask is one GET per held candidate per tick, and a window whose whole remaining chain is
   * waiting on releases would make that same call every fifteen seconds for hours. So an answer is
   * reused for {@code qits.maintenance.bump.dispatch.release-state-ttl} — long enough to collapse
   * the repetition, far shorter than any release takes to settle, and bounded by the number of
   * requests this service has opened rather than by time (a window's opening clears it).
   */
  private record Seen(ReleaseRequestClient.ReleaseState state, Instant at) {}

  private final Map<String, Seen> releaseStates = new java.util.concurrent.ConcurrentHashMap<>();

  /** Opens the dispatch window. The cron's whole job. */
  public void open(Instant now) {
    Instant closes = now.plus(config.bumpWindow());
    synchronized (refused) {
      refused.clear();
    }
    releaseStates.clear();
    store.openBumpWindow(now, closes);
    LOG.infof(
        "The bump dispatch window is open until %s; one bump goes at a time, from the bottom of the"
            + " chain, whenever qits-ci is idle.",
        closes);
  }

  /** Closes it, saying why. Idempotent — a window that is already shut logs nothing. */
  public void close(String why) {
    if (store.bumpWindow().isPresent()) {
      store.closeBumpWindow();
      LOG.infof("The bump dispatch window is closed: %s.", why);
    }
  }

  /** Whether anything would be dispatched at all right now. */
  public boolean windowOpen(Instant now) {
    return store.bumpWindow().filter(now::isBefore).isPresent();
  }

  /**
   * A repository that is owed a bump and cannot be given one, because the release its last bump
   * asked for has stopped happening.
   *
   * @param repository the repository, as the catalog spells it
   * @param group the group whose branch is waiting
   * @param requestId the release request in qits-projects
   * @param state that request's state — REJECTED, FAILED, CONFLICTED, WITHDRAWN
   * @param reason that service's own sentence, which is usually the failing gating run
   */
  public record Stalled(
      String repository, String group, String requestId, String state, String reason) {}

  /** Every repository owed a bump this tick, split into what may be sent and what is stuck. */
  public record Assessment(List<BumpOrder.Candidate> candidates, List<Stalled> stalled) {}

  /**
   * What the gate decided and everything it decided it from — the tick's whole reasoning, so that
   * "there are pending bumps and nothing is queued" is a question this service answers about itself
   * rather than one somebody reconstructs from three services' logs.
   *
   * @param outcome the short name of the gate that answered
   * @param summary the sentence for a person
   * @param inFlight bumps of ours not yet ended, or null when the gate answered before asking
   * @param allowed how many may be in flight
   * @param ciActive what qits-ci holds, null when it was not asked or could not be read
   * @param owed how many repositories are owed a bump and could still be sent one
   * @param held how many of those are waiting on a release in flight
   * @param stalled the ones waiting on a release that has stopped
   * @param pick what would be dispatched, or null
   */
  public record Decision(
      String outcome,
      String summary,
      Integer inFlight,
      Integer allowed,
      Integer ciActive,
      int owed,
      int held,
      List<Stalled> stalled,
      BumpOrder.Pick pick) {

    static Decision of(String outcome, String summary) {
      return new Decision(outcome, summary, null, null, null, 0, 0, List.of(), null);
    }

    Decision with(Integer inFlight, Integer allowed, Integer ciActive) {
      return new Decision(
          outcome, summary, inFlight, allowed, ciActive, owed, held, stalled, pick);
    }
  }

  /**
   * One dispatch decision.
   *
   * @return the bump that was asked for, or empty — which is every other outcome, and none of them
   *     is a failure
   */
  public Optional<UUID> tick() {
    Decision decision = decide(Instant.now(), true);
    if (decision.pick() == null) {
      return Optional.empty();
    }
    return dispatch(decision.pick(), decision.owed());
  }

  /**
   * The same reasoning with nothing done about it — no window closed, no bump sent — for the door
   * that answers "why is nothing happening".
   */
  public Decision explain(Instant now) {
    return decide(now, false);
  }

  /**
   * The gates, in the order they are asked.
   *
   * @param commit whether to act on what is decided: close a window that is over or empty, and
   *     answer a pick that the caller will dispatch. A read-only pass changes nothing at all except
   *     the cached release states, which are an observation either way.
   */
  private Decision decide(Instant now, boolean commit) {
    Optional<Instant> closes = store.bumpWindow();
    if (closes.isEmpty()) {
      return Decision.of("NO_WINDOW", "no dispatch window is open");
    }
    if (!now.isBefore(closes.get())) {
      if (commit) {
        close("it ended with work still owed; the next schedule opens a new one");
      }
      return Decision.of("WINDOW_OVER", "the window has expired and closes on this tick");
    }
    if (!config.bumpEnabled()) {
      if (commit) {
        close("qits.maintenance.bump.enabled is false");
      }
      return Decision.of("DISABLED", "qits.maintenance.bump.enabled is false");
    }
    if (!config.bumpInternalAuto()) {
      if (commit) {
        close("qits.maintenance.bump.internal.auto is false");
      }
      return Decision.of("DISABLED", "qits.maintenance.bump.internal.auto is false");
    }

    // THE IN-FLIGHT COUNT IS ASKED BEFORE THE CANDIDATES, and the order is what keeps the window
    // from shutting on its own work: the repository being bumped right now is not a candidate — an
    // active bump is a skip — so a check the other way round would read "nothing is owed" while the
    // night's last bump is still running.
    //
    // AND IT COUNTS TARGETED BUMPS TOO, deliberately. Everything else about a targeted bump is
    // outside this gate — it is not a candidate, it holds no group's lock and it waits on no release
    // — but it IS a CI run this service asked for, and the whole of what this number is for is not
    // handing qits-ci more than it can take. A count that saw only the nightly half would open the
    // valve at exactly the moment somebody's release request had a build going.
    int allowed = config.bumpMaxInFlight();
    int inFlight = store.activeBumps().size();
    if (inFlight >= allowed) {
      LOG.debugf(
          "%d bump(s) are in flight and %d are allowed; nothing is dispatched.", inFlight, allowed);
      return Decision.of(
              "IN_FLIGHT", inFlight + " bump(s) of ours are still running and " + allowed + " may be")
          .with(inFlight, allowed, null);
    }

    Assessment assessment = assess();
    List<BumpOrder.Candidate> candidates = assessment.candidates();
    List<Stalled> stalled = assessment.stalled();
    int heldCount = (int) candidates.stream().filter(BumpOrder.Candidate::held).count();
    if (candidates.isEmpty()) {
      // NOTHING DISPATCHABLE IS OWED, and a window whose remainder is stalled closes here too. It
      // must: staying open for work that cannot be done is a night that never ends and a gate that
      // reports nothing. The sentence names the stalled repositories, which is the one line the
      // fourth live failure of this gate did not have.
      String why =
          stalled.isEmpty()
              ? "nothing is owed a bump"
              : "nothing is owed a bump that can be sent; "
                  + stalled.size()
                  + " wait on a release that has stopped ("
                  + stalledNames(stalled)
                  + ")";
      if (commit) {
        close(why);
      }
      return new Decision(
          stalled.isEmpty() ? "NOTHING_OWED" : "ALL_STALLED",
          why,
          inFlight,
          allowed,
          null,
          0,
          0,
          stalled,
          null);
    }

    CiClient.QueueState queue = ci.activeRuns();
    if (!queue.readable()) {
      // Unreadable is BUSY. Dispatching blind here is precisely the wavefront this gate exists for.
      LOG.debugf("qits-ci's queue could not be read (%s); nothing is dispatched.", queue.error());
      return new Decision(
          "CI_UNREADABLE",
          "qits-ci's queue could not be read (" + queue.error() + "), which counts as busy",
          inFlight,
          allowed,
          null,
          candidates.size(),
          heldCount,
          stalled,
          null);
    }
    if (queue.active() >= allowed) {
      LOG.debugf(
          "qits-ci holds %d active run(s) and %d are allowed; nothing is dispatched.",
          queue.active(), Integer.valueOf(allowed));
      return new Decision(
          "CI_BUSY",
          "qits-ci holds " + queue.active() + " active run(s) and " + allowed + " may be",
          inFlight,
          allowed,
          queue.active(),
          candidates.size(),
          heldCount,
          stalled,
          null);
    }

    Optional<BumpOrder.Pick> pick = BumpOrder.next(candidates, artifacts.producers());
    if (pick.isEmpty()) {
      // Everything owed is waiting on a release that has already been asked for. An ordinary state
      // and not a stall: DEBUG, no cycle break, and the window stays open for what comes after it.
      LOG.debugf(
          "All %d owed bump(s) are waiting on a release of their own branch; nothing is dispatched.",
          candidates.size());
      return new Decision(
          "WAITING_ON_RELEASES",
          "all " + candidates.size() + " owed bump(s) wait on a release of their own branch",
          inFlight,
          allowed,
          queue.active(),
          candidates.size(),
          heldCount,
          stalled,
          null);
    }
    return new Decision(
        "DISPATCH",
        "the next bump is " + pick.get().candidate().repository(),
        inFlight,
        allowed,
        queue.active(),
        candidates.size(),
        heldCount,
        stalled,
        pick.get());
  }

  private static String stalledNames(List<Stalled> stalled) {
    List<String> names = new ArrayList<>();
    for (Stalled one : stalled) {
      names.add(one.repository() + " " + one.state());
    }
    return String.join(", ", names);
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
   *
   * <p><b>The last of them is a flag rather than a skip.</b> A repository whose branch is already
   * pushed and waiting on a release is still owed — see this class's javadoc — so it is returned
   * {@linkplain BumpOrder.Candidate#held() held} instead of being dropped: dropping it would let its
   * consumers go early and would let the window close on a chain that is only half sent.
   *
   * <p>One extra row read per candidate per tick, and only for the repositories that got as far as
   * having something pending — at roughly fifty repositories that is cheaper than the CI run a
   * single re-dispatch costs.
   */
  public List<BumpOrder.Candidate> candidates() {
    return assess().candidates();
  }

  /** The same walk, keeping what it had to drop and why. */
  public Assessment assess() {
    String group = GroupConfig.DEFAULT_GROUP;
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    List<BumpOrder.Candidate> candidates = new ArrayList<>();
    List<Stalled> stalled = new ArrayList<>();
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
      Hold hold = hold(row, group, changes);
      if (hold.stalled() != null) {
        stalled.add(hold.stalled());
        continue;
      }
      candidates.add(new BumpOrder.Candidate(row.name, group, changes, hold.held()));
    }
    return new Assessment(List.copyOf(candidates), List.copyOf(stalled));
  }

  /**
   * How a candidate stands against the bump it has already had: free, held, or stalled.
   *
   * @param held these exact changes are on a branch whose release is on its way
   * @param stalled …or on a branch whose release has stopped, with the reason
   */
  private record Hold(boolean held, Stalled stalled) {

    static final Hold FREE = new Hold(false, null);
    static final Hold HELD = new Hold(true, null);
  }

  /**
   * Whether this group's branch has already been bumped for exactly these changes and is waiting on
   * the release that will move main.
   */
  private Hold hold(MtRepository row, String group, List<Change> pending) {
    Optional<MtBump> newest = store.newestBump(row.name, group);
    if (newest.isEmpty()) {
      return Hold.FREE;
    }
    MtBump bump = newest.get();
    BumpStatus status = BumpStatus.valueOf(bump.status);
    if (status != BumpStatus.SUCCEEDED && status != BumpStatus.NOTHING_TO_DO) {
      // REQUESTED and RUNNING never reach here — an active bump is a skip above — and FAILED must
      // stay retryable: the branch it was going to push is not there to be released.
      return Hold.FREE;
    }
    if (!sameChanges(BumpService.changes(bump), pending)) {
      return Hold.FREE;
    }
    return releaseHold(row, group, bump);
  }

  /**
   * Whether the release that bump asked for is still one to wait for.
   *
   * <p>Held on everything that is not a plain "this has stopped": a request id nothing can be asked
   * about (the {@code converged} sentinel, an ask that has not been made yet — the sweep is still
   * re-attempting it), a repository with no catalog id to address qits-projects with, and any answer
   * that could not be read. <b>{@code refused} is the one sentinel that stalls</b>: qits-projects
   * refused the ask itself, so there is no request and nothing is coming.
   */
  private Hold releaseHold(MtRepository row, String group, MtBump bump) {
    String requestId = bump.releaseRequestId;
    if (requestId == null || requestId.isBlank() || ReleaseRequestClient.CONVERGED.equals(requestId)) {
      return Hold.HELD;
    }
    if (ReleaseRequestClient.REFUSED.equals(requestId)) {
      return new Hold(
          false,
          new Stalled(row.name, group, null, "REFUSED", bump.message == null ? "" : bump.message));
    }
    if (row.catalogId == null || row.catalogId.isBlank()) {
      return Hold.HELD;
    }
    ReleaseRequestClient.ReleaseState state = releaseState(row.catalogId, requestId, bump);
    if (!state.stalled()) {
      return Hold.HELD;
    }
    return new Hold(
        false,
        new Stalled(
            row.name,
            group,
            requestId,
            state.state(),
            state.detail() == null ? "" : state.detail()));
  }

  /**
   * qits-projects' answer about one request, reused within the ttl and written onto the bump row
   * whenever it is freshly read.
   */
  private ReleaseRequestClient.ReleaseState releaseState(
      String catalogId, String requestId, MtBump bump) {
    Instant now = Instant.now();
    Seen seen = releaseStates.get(requestId);
    if (seen != null && seen.at().plus(config.bumpReleaseStateTtl()).isAfter(now)) {
      return seen.state();
    }
    ReleaseRequestClient.ReleaseState state = releases.state(catalogId, requestId);
    releaseStates.put(requestId, new Seen(state, now));
    if (state.readable()) {
      String before = bump.releaseState;
      store.bumpReleaseState(bump.id, state.state(), state.detail(), now);
      if (state.stalled() && !state.state().equals(before)) {
        // ONE WARN WHEN IT TURNS, and none while it stays that way. This is the line whose absence
        // made the estate look idle for four hours.
        LOG.warnf(
            "The release request %s of %s is %s, so its bump is not waited on any longer: %s",
            requestId, bump.repository, state.state(), state.sentence());
      }
    } else {
      LOG.debugf("The release request %s could not be read: %s", requestId, state.error());
    }
    return state;
  }

  /**
   * Whether two change lists ask for the same thing, order-insensitively.
   *
   * <p><b>Compared on (ecosystem, name, from, to) and not on the whole record.</b> Those four are
   * what the bump is FOR; {@code manifestPath} and {@code location} say how the edit is applied and
   * a scan that re-read the same repository can legitimately spell them differently — a pin that
   * moved file, a property that became an element — without any dependency having moved. Comparing
   * those too would report a new bump every time a manifest was reshaped, which is the
   * re-dispatch loop this exists to stop.
   */
  private static boolean sameChanges(List<Change> recorded, List<Change> pending) {
    return keys(recorded).equals(keys(pending));
  }

  private static Set<String> keys(List<Change> changes) {
    Set<String> keys = new LinkedHashSet<>();
    for (Change change : changes) {
      keys.add(change.ecosystem() + " " + change.name() + " " + change.from() + " " + change.to());
    }
    return keys;
  }
}
