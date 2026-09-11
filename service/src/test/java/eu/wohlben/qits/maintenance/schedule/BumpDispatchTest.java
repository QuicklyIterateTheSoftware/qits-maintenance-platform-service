package eu.wohlben.qits.maintenance.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * THE GATE: a bump leaves only when qits-ci has nothing to do.
 *
 * <p>The subject here is the DISPATCH DECISION rather than the selection — which repository goes
 * first is {@code BumpOrderTest}'s, and it is stated there against a graph rather than against this
 * fixture's one repository. What this pins is the part no unit test can: that the window, the CI
 * queue read and the in-flight count are actually wired to the thing that sends.
 *
 * <p><b>Every method drives {@link BumpDispatcher#tick()} by hand.</b> The suite's scheduler is off,
 * so a test that waited for the timer would be indistinguishable from a test that hung.
 */
@QuarkusTest
class BumpDispatchTest {

  @Inject BumpDispatcher dispatcher;

  @Inject ScanService scans;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void scriptThePeers() {
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
    Fixture.scriptCiAccepts(peers, "run-dispatched");
    Fixture.scriptReleaseRequestAccepted(peers, "rr-dispatched");
    scans.request(ScanScope.ALL, null, ScanTrigger.MANUAL);
    queue.awaitIdle(Duration.ofSeconds(60));
    dispatcher.close("a fresh test");
  }

  private boolean bumped() {
    queue.awaitIdle(Duration.ofSeconds(30));
    return !store.bumps(Fixture.REPOSITORY, 50).isEmpty();
  }

  /**
   * <b>THE FIFTH LIVE FAILURE, AND THE WHOLE OF THIS FIX: DEBT ARMS THE DISPATCH, NOT THE HOUR.</b>
   * On 2026-09-11 {@code @qits/ui-components} was cut at 10:44, fifteen repositories were owed by
   * 10:45 and at 17:20 none of them had been sent: every gate would have passed, but the first one
   * could only be opened by a 02:00 cron. The intended primary upgrade path — something becomes
   * owed, qits-ci goes idle, the bump is dispatched — had never once run on its own.
   *
   * <p><b>So this test never calls {@link BumpDispatcher#open}</b>, and it is the exact scenario of
   * the ticket: no window row, an owed repository, an idle queue. The tick sends, and the window it
   * opened for itself is there afterwards.
   */
  @Test
  void anOwedBumpAndAnIdleQueueDispatchWithNoWindowAndNoCron() {
    Fixture.scriptCiQueueEmpty(peers);
    assertTrue(store.bumpWindow().isEmpty(), "no cron has run and nobody pressed the door");

    assertTrue(dispatcher.tick().isPresent(), "the debt is the reason, and it is enough");
    assertTrue(bumped());
    assertTrue(
        store.bumpWindow().isPresent(),
        "and the window is a consequence of the debt rather than of an hour");
  }

  /** Nothing owed is still nothing dispatched, and it opens no window to find that out. */
  @Test
  void nothingIsDispatchedWhenNothingIsOwed() {
    Fixture.scriptCiQueueEmpty(peers);
    inventory.clearLatest();

    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(bumped());
    assertTrue(store.bumpWindow().isEmpty(), "a window with nothing to hand out is not opened");
  }

  /**
   * The whole point: a busy qits-ci is not handed another build. <b>And it is asked without a
   * window row</b>, because debt is what arms this now — the capacity gate is the one that decides
   * whether the owed work goes, and it is unchanged.
   */
  @Test
  void aBusyQueueDispatchesNothingAndAnEmptyOneDispatchesOne() {
    Fixture.scriptCiQueue(peers, 2);

    assertTrue(dispatcher.tick().isEmpty(), "two runs are active and one is allowed");
    assertFalse(bumped());

    // The estate goes quiet, and the same tick that declined now sends.
    Fixture.scriptCiQueueEmpty(peers);
    assertTrue(dispatcher.tick().isPresent(), "an empty queue is what it was waiting for");
    assertTrue(bumped());
  }

  /**
   * <b>UNREADABLE IS BUSY.</b> A gate that read "I could not ask" as "nothing is going" would fire
   * the whole night's bumps at the one moment qits-ci is least able to say so — which is worse than
   * the stampede it replaced, because it would be aimed at a service already in trouble.
   */
  @Test
  void aQueueThatCannotBeReadIsTreatedAsBusy() {
    peers.answer(
        PeerTarget.CI,
        CiClient.ACTIVE_RUNS_PATH,
        FakePeers.Scripted.unreachable("connection refused"));
    dispatcher.open(Instant.now());

    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(bumped(), "nothing is dispatched blind");
  }

  /** One at a time: the bump it just sent is the reason the next tick declines. */
  @Test
  void aBumpInFlightHoldsTheNextOne() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    assertTrue(dispatcher.tick().isPresent());
    queue.awaitIdle(Duration.ofSeconds(30));

    // The fixture's one repository has no second group the clock owns, so this asserts the state
    // rather than a second candidate — the bump is RUNNING and the count is against the same knob.
    assertEquals(1, store.activeBumps().size());
    assertTrue(dispatcher.tick().isEmpty(), "one is in flight and one is allowed");
  }

  /** The ordinary ending: the window shuts itself the moment nothing is owed. */
  @Test
  void theWindowClosesItselfWhenEverythingOwedHasBeenAskedFor() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());
    assertTrue(dispatcher.windowOpen(Instant.now()));

    UUID id = dispatcher.tick().orElseThrow();
    queue.awaitIdle(Duration.ofSeconds(30));
    // The bump has to END before the window can: while it is in flight the count declines the tick
    // without ever asking what is owed. Ended here rather than driven through qits-ci, because what
    // this test is about is the window and not the run.
    store.bumpFinished(id, BumpStatus.NOTHING_TO_DO, "SUCCESS", "ended by the test", Instant.now());
    inventory.clearLatest();

    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(dispatcher.windowOpen(Instant.now()), "nothing is owed, so the night is over");
  }

  /**
   * <b>THE LIVE FAILURE, END TO END.</b> At 05:52 one repository was dispatched, came back
   * SUCCEEDED with a release request open, and was dispatched again thirty seconds later — then
   * again, and again, every one of those a CI run that could only answer NOTHING_TO_DO. Pending is
   * read off the pins on <i>main</i>, and main does not move until that release lands, so the
   * repository is genuinely still owed; what it is not, is sendable.
   *
   * <p>The two assertions are one fact each and both matter: no second bump, and <b>the window is
   * still open</b>. Held has to keep counting as owed, or a night whose chain is waiting on releases
   * would declare itself finished and lose everything behind it.
   */
  @Test
  void aBumpWhoseBranchIsWaitingOnItsReleaseIsNotDispatchedAgain() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = dispatcher.tick().orElseThrow();
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(
        id, BumpStatus.SUCCEEDED, "SUCCESS", "the branch is pushed and its release is open", Instant.now());

    assertTrue(dispatcher.tick().isEmpty(), "the same changes have already been asked for");
    assertEquals(1, store.bumps(Fixture.REPOSITORY, 50).size(), "and no second row was written");
    assertTrue(
        dispatcher.windowOpen(Instant.now()),
        "held is still owed: the window must not close on a chain that is only half sent");
  }

  /**
   * The same hold for the other ending that leaves the pins where they were. NOTHING_TO_DO is what
   * the re-dispatch loop kept producing, and a run that found nothing to write is the strongest
   * possible evidence that sending it once more would find nothing either.
   */
  @Test
  void aBumpThatFoundNothingToDoAlsoHoldsItsRepositoryBack() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = dispatcher.tick().orElseThrow();
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(
        id, BumpStatus.NOTHING_TO_DO, "SUCCESS", "the versions were already there", Instant.now());

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(1, store.bumps(Fixture.REPOSITORY, 50).size());
  }

  /**
   * <b>A FAILURE HOLDS NOTHING.</b> There is no branch waiting on a release — the run went red, or
   * qits-ci recorded no run at all — so the work is owed in the plainest sense and the next tick
   * inside the window is exactly the retry.
   */
  @Test
  void aFailedBumpIsRetriedRatherThanHeld() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = dispatcher.tick().orElseThrow();
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.FAILED, "FAILED", "the run went red", Instant.now());

    assertTrue(dispatcher.tick().isPresent(), "a failure must stay retryable");
    assertEquals(2, store.bumps(Fixture.REPOSITORY, 50).size());
  }

  /**
   * <b>THE SECOND LIVE FAILURE: THE NIGHT MUST SURVIVE THIS SERVICE'S OWN REDEPLOY.</b> On
   * 2026-09-10 nineteen bumps went out one at a time from 06:32, and one of them was the bump of
   * {@code qits-maintenance-platform-service} itself. It succeeded at 08:01, its release deployed at
   * 08:11, the container was replaced — and with the window held in a field, the night ended there:
   * eleven repositories owed, four hours of window left, and no cron until 02:00.
   *
   * <p><b>So this test never calls {@link BumpDispatcher#open}.</b> The window is written to the
   * store as a previous process left it, and the dispatcher — which is what a restarted one is —
   * picks the night up from the row.
   */
  @Test
  void aWindowOpenedBeforeARestartIsResumedFromTheStore() {
    Fixture.scriptCiQueueEmpty(peers);
    Instant now = Instant.now();
    store.openBumpWindow(now.minus(Duration.ofHours(2)), now.plus(Duration.ofHours(4)));

    assertTrue(dispatcher.windowOpen(now), "the row is the window; the field was only a cache");
    assertTrue(dispatcher.tick().isPresent(), "the night carries on where the last process left it");
    assertTrue(bumped());
  }

  /**
   * <b>AN EXPIRY IS A RESET, NOT A GUILLOTINE.</b> An expired row is closed — it is never read as a
   * window that is still running — and then the same tick asks the only question that decides
   * anything now: is something owed. It is, so a fresh window opens and the chain carries on, which
   * is the half the old behaviour got wrong: what a window did not reach by its sixth hour was
   * silently dropped until the next night, and one {@code @qits/ui-components} release is roughly
   * twenty-eight dispatches deep.
   */
  @Test
  void anExpiredWindowIsReplacedRatherThanDroppingTheWorkItDidNotReach() {
    Fixture.scriptCiQueueEmpty(peers);
    Instant now = Instant.now();
    Instant stale = now.minus(Duration.ofHours(1));
    store.openBumpWindow(now.minus(Duration.ofHours(7)), stale);

    assertFalse(dispatcher.windowOpen(now), "the row is over and is not treated as open");
    assertTrue(dispatcher.tick().isPresent(), "the work is still owed, so it still goes");
    assertTrue(bumped());
    assertTrue(
        store.bumpWindow().orElseThrow().isAfter(stale),
        "and the expired row was replaced rather than re-read every tick");
  }

  /**
   * <b>THE FOURTH LIVE FAILURE: A HOLD THAT WAITS FOR A RELEASE THAT IS NOT COMING.</b> On
   * 2026-09-10 the night's twentieth bump pushed its branch at 07:05 and its release request was
   * REJECTED nine minutes later — the repository's gating build does not compile. Four hours on, the
   * window was still open, qits-ci was idle, that one repository was the only thing owed, nothing
   * had been dispatched since 10:54, and no line anywhere said why. A dead release and a release in
   * flight were the same state, and a dead one holds for ever.
   *
   * <p>Three assertions, one fact each: it is not dispatched again (the branch already carries the
   * change, so a fresh bump could only answer NOTHING_TO_DO), it is <b>not a candidate at all</b> —
   * so it stops holding its consumers back — and the window therefore <b>closes</b>, because a night
   * must not stay open for work that cannot be done.
   */
  @Test
  void aBumpWhoseReleaseWasRejectedStopsBeingWaitedOnAndSaysSo() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = dispatcher.tick().orElseThrow();
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    store.bumpReleaseAsked(id, "rr-rejected", "the release request rr-rejected is PENDING");
    Fixture.scriptReleaseRequestState(
        peers, "rr-rejected", "REJECTED", "Gating run 3248b7f4 finished FAILED");

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("ALL_STALLED", decision.outcome());
    assertEquals(0, decision.owed(), "a release that has stopped is not work this gate can do");
    assertEquals(1, decision.stalled().size());
    assertEquals(Fixture.REPOSITORY, decision.stalled().get(0).repository());
    assertEquals("REJECTED", decision.stalled().get(0).state());
    assertTrue(
        decision.stalled().get(0).reason().contains("3248b7f4"),
        "and it carries qits-projects' own sentence, which is the failing gating run");

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(1, store.bumps(Fixture.REPOSITORY, 50).size(), "no second, pointless CI run");
    assertFalse(
        dispatcher.windowOpen(Instant.now()),
        "and the night ends rather than standing open on a build that needs a person");
  }

  /**
   * <b>The ordinary case is unchanged, and that is the half worth pinning.</b> A release that is
   * still PENDING is exactly what a hold is for: not dispatched, still owed, window still open.
   */
  @Test
  void aReleaseStillOnItsWayHoldsExactlyAsItDidBefore() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = dispatcher.tick().orElseThrow();
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    store.bumpReleaseAsked(id, "rr-open", "the release request rr-open is PENDING");
    Fixture.scriptReleaseRequestState(peers, "rr-open", "PENDING", null);

    assertTrue(dispatcher.tick().isEmpty());
    assertEquals(1, store.bumps(Fixture.REPOSITORY, 50).size());
    assertTrue(dispatcher.windowOpen(Instant.now()), "something is still coming");
    assertEquals(
        "PENDING",
        store.bump(id).orElseThrow().releaseState,
        "and what was read is on the row, so a bump standing for hours explains itself");
  }

  /**
   * <b>UNREADABLE IS NOT STALLED</b>, the same ruling the CI queue gets one gate down: a peer that
   * could not be asked is evidence about nothing. Reading it as "the release has stopped" would drop
   * a repository out of the night — and let its consumers build against the old pin — because
   * qits-projects restarted.
   */
  @Test
  void aReleaseRequestThatCannotBeReadIsHeldRatherThanStalled() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = dispatcher.tick().orElseThrow();
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    store.bumpReleaseAsked(id, "rr-silent", "the release request rr-silent is PENDING");
    Fixture.scriptReleaseRequestStateUnreachable(peers, "rr-silent");

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals(1, decision.owed());
    assertEquals(1, decision.held());
    assertTrue(decision.stalled().isEmpty());
    assertTrue(dispatcher.tick().isEmpty());
    assertTrue(dispatcher.windowOpen(Instant.now()));
  }

  /**
   * <b>A stall is not a verdict.</b> qits-projects re-arms REJECTED back to PENDING on the next
   * merged sha — a push to the branch, a sibling's release, a pending tag reaching main — so the
   * answer is asked again on every tick and a request that came back to life is held again with
   * nothing to unwind. Recording the rejection would have kept the repository out of every night
   * after the thing that rejected it was fixed.
   */
  @Test
  void aRejectionThatIsReArmedIsWaitedOnAgain() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = dispatcher.tick().orElseThrow();
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    store.bumpReleaseAsked(id, "rr-rearmed", "the release request rr-rearmed is PENDING");
    Fixture.scriptReleaseRequestState(peers, "rr-rearmed", "REJECTED", "a red gate");
    assertEquals(1, dispatcher.explain(Instant.now()).stalled().size());

    // Somebody pushes the fix; the fold re-arms.
    Fixture.scriptReleaseRequestState(peers, "rr-rearmed", "PENDING", null);
    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertTrue(decision.stalled().isEmpty());
    assertEquals(1, decision.held(), "held again, with no state of ours to undo");
  }

  /**
   * <b>"FIFTEEN OWED, NOTHING SENT" AND "THE SCHEDULER IS DEAD" MUST NOT LOOK THE SAME.</b> The
   * bump listing holds only bumps that were dispatched and the window door used to 404 with no
   * window open, so between two dispatches this service said nothing at all about the work it was
   * holding. The owed set is answered in dispatch order with each entry's reason — here, from
   * behind a busy queue and with no window row anywhere.
   */
  @Test
  void theOwedSetIsReportedWithItsReasonsWhileNothingIsBeingDispatched() {
    Fixture.scriptCiQueue(peers, 2);

    BumpDispatcher.Decision decision = dispatcher.explain(Instant.now());
    assertEquals("CI_BUSY", decision.outcome());
    assertEquals(1, decision.owed());
    assertEquals(1, decision.queue().size());
    assertEquals(Fixture.REPOSITORY, decision.queue().get(0).repository());
    assertEquals("READY", decision.queue().get(0).reason());
    assertTrue(store.bumpWindow().isEmpty(), "and reading changed nothing");
  }

  /** The same answer at the door, which is where an operator asks it — 200, not a 404. */
  @Test
  void theWindowDoorAnswersTheOwedSetWithNoWindowOpen() {
    Fixture.scriptCiQueue(peers, 2);

    io.restassured.RestAssured.given()
        .get("/maintenance/api/bumps/window")
        .then()
        .statusCode(200)
        .body("open", org.hamcrest.Matchers.equalTo(false))
        .body("openedAt", org.hamcrest.Matchers.nullValue())
        .body("outcome", org.hamcrest.Matchers.equalTo("CI_BUSY"))
        .body("owed", org.hamcrest.Matchers.equalTo(1))
        .body("queue[0].repository", org.hamcrest.Matchers.equalTo(Fixture.REPOSITORY))
        .body("queue[0].reason", org.hamcrest.Matchers.equalTo("READY"));
  }

  /**
   * <b>THE HOLD IS ON THE CHANGES, NOT ON THE REPOSITORY.</b> An upstream released while the first
   * branch was waiting, so the pending set is no longer the set that was sent — that is a different
   * bump, and refusing it would sit on a genuinely new version until the window expired.
   */
  @Test
  void aPendingSetThatMovedSinceTheLastBumpGoesAgain() {
    Fixture.scriptCiQueueEmpty(peers);
    dispatcher.open(Instant.now());

    UUID id = dispatcher.tick().orElseThrow();
    queue.awaitIdle(Duration.ofSeconds(30));
    store.bumpFinished(id, BumpStatus.SUCCEEDED, "SUCCESS", "pushed", Instant.now());
    assertTrue(dispatcher.tick().isEmpty(), "held, until something moves");

    // One of the dependencies it just asked for releases again. Nothing else about the repository
    // changes: the same group, the same branch, one different `to`.
    Change moved =
        BumpService.changes(store.bump(id).orElseThrow()).stream()
            .filter(change -> Ecosystem.MAVEN.wireName().equals(change.ecosystem()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the internal group carries a maven change"));
    store.recordLatestIfNewer(
        Ecosystem.MAVEN, moved.name(), "2029.101.1", "test", Instant.now());

    assertTrue(dispatcher.tick().isPresent(), "a new upstream release is a new bump");
    assertEquals(2, store.bumps(Fixture.REPOSITORY, 50).size());
  }
}
