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

  /** No window, no dispatch — and the pending changes are there the whole time. */
  @Test
  void nothingIsDispatchedOutsideAWindow() {
    Fixture.scriptCiQueueEmpty(peers);

    assertTrue(dispatcher.tick().isEmpty(), "the cron has not opened one");
    assertFalse(bumped());
    assertFalse(
        peers.called(PeerTarget.CI, CiClient.ACTIVE_RUNS_PATH),
        "and nothing was asked of qits-ci: outside a window this costs one row read");
  }

  /** The whole point: a busy qits-ci is not handed another build. */
  @Test
  void aBusyQueueDispatchesNothingAndAnEmptyOneDispatchesOne() {
    Fixture.scriptCiQueue(peers, 2);
    dispatcher.open(Instant.now());

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
   * <b>And resuming is not the same as never ending.</b> A service that was down for the whole six
   * hours comes back to a window that is already over, and the first tick shuts it rather than
   * dispatching a night's worth of bumps at whatever hour it happened to start.
   */
  @Test
  void aWindowThatExpiredWhileTheServiceWasDownIsClosedOnTheFirstTick() {
    Fixture.scriptCiQueueEmpty(peers);
    Instant now = Instant.now();
    store.openBumpWindow(now.minus(Duration.ofHours(7)), now.minus(Duration.ofHours(1)));

    assertFalse(dispatcher.windowOpen(now));
    assertTrue(dispatcher.tick().isEmpty());
    assertFalse(bumped(), "02:00's window does not get to fire at 09:00");
    assertTrue(store.bumpWindow().isEmpty(), "and the row is gone rather than re-read every tick");
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
