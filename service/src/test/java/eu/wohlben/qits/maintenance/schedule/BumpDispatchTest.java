package eu.wohlben.qits.maintenance.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.Fixture;
import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
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
        "and nothing was asked of qits-ci: outside a window this costs one field read");
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
}
