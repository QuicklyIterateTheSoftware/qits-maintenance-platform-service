package eu.wohlben.qits.maintenance.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.entity.MtTrain;
import eu.wohlben.qits.maintenance.entity.MtTrainNode;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.TrainEndKind;
import eu.wohlben.qits.maintenance.model.TrainNodeState;
import eu.wohlben.qits.maintenance.model.TrainStatus;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The config-pin sweep: the two release-train ends nothing announces.</b>
 *
 * <p>Every rule lives in {@code train/TrainSweep} rather than in the schedule in front of it, which
 * is what lets all of this be driven by a method call — the suite's scheduler is off, and a test that
 * waited for a cron would be indistinguishable from a test that hung.
 *
 * <p><b>The peers are the real seam.</b> {@link FakePeers} replaces {@code PeerClient.get}, so the
 * urls asserted below are resolved from the SHIPPED target configuration: a wrong path, a wrong
 * target or a forgotten {@code PeerTarget} entry fails here rather than in a deployment.
 *
 * <p><b>The estate is built by hand rather than by a scan.</b> What decides these rules is an
 * archetype, a docker artifact row and a train — three writes — and driving a whole scan to produce
 * them would test the scan.
 */
@QuarkusTest
class TrainSweepTest {

  /** The image repository, its image, and the application that deploys it. */
  private static final String IMAGE_REPOSITORY = "qits-workspace-oci";
  private static final String IMAGE = "qits/workspace";
  private static final String APPLICATION = "qits-workspaces";

  /** The daemon, and the service that hands it out — {@code TrainService.DAEMON_ADOPTERS}. */
  private static final String DAEMON = "qits-ci-daemon";
  private static final String DAEMON_ADOPTER = "qits-ci";

  private static final String VERSION = "2026.905.4";
  private static final Instant WHEN = Instant.parse("2026-09-05T09:00:00Z");

  @Inject TrainSweep sweep;

  @Inject TrainService trains;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject InventoryReset reset;

  @BeforeEach
  void anEmptyEstate() {
    reset.clear();
    peers.reset();
  }

  // --- the fixtures -------------------------------------------------------------------------------

  private void scanned(String repository, RepositoryArchetype archetype) {
    store.replaceInventory(
        repository,
        "qits",
        null,
        archetype.name(),
        "main",
        RepositoryStatus.OK,
        "sha",
        null,
        List.of(),
        List.of(),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        WHEN);
  }

  /** An IMAGE repository that has released its image — the artifact row and the station. */
  private MtTrain anImageRelease(String version) {
    scanned(IMAGE_REPOSITORY, RepositoryArchetype.IMAGE);
    store.upsertArtifact(Ecosystem.DOCKER, IMAGE, version, IMAGE_REPOSITORY, WHEN);
    return trains
        .spawn(
            IMAGE_REPOSITORY,
            version,
            new TrainService.ReleasedPackage(Ecosystem.DOCKER, IMAGE),
            WHEN)
        .train();
  }

  /** A DAEMON repository that has released. Nothing pins a daemon, so there is no artifact row. */
  private MtTrain aDaemonRelease(String version) {
    scanned(DAEMON, RepositoryArchetype.DAEMON);
    return trains.spawn(DAEMON, version, null, WHEN).train();
  }

  /** qits-configuration's answer, one row. */
  private void configurationPins(String image, String version, String application) {
    configurationAnswer(
        "{\"generatedAt\":\"2026-09-05T10:00:00Z\",\"pins\":[{\"image\":\"" + image
            + "\",\"version\":\"" + version + "\",\"application\":\"" + application
            + "\",\"key\":\"env.QITS_WORKSPACE_IMAGE_VERSION\"}]}");
  }

  private void configurationAnswer(String body) {
    peers.answer(PeerTarget.CONFIGURATION, ConfigPinsClient.PATH, FakePeers.Scripted.ok(body));
  }

  /** qits-ci's ladder. */
  private void ladder(String daemon, String version, String source) {
    peers.answer(
        PeerTarget.CI,
        DaemonPinClient.PATH,
        FakePeers.Scripted.ok(
            "{\"daemonName\":\"" + daemon + "\",\"daemonVersion\":\"" + version
                + "\",\"previousDaemonVersion\":\"\",\"source\":\"" + source + "\"}"));
  }

  private MtTrainNode node(MtTrain train, String consumer) {
    return store.trainNodes(train.id).stream()
        .filter(row -> consumer.equals(row.consumer))
        .findFirst()
        .orElseThrow(() -> new AssertionError(consumer + " is not on the train of " + train.repository));
  }

  // --- the idle estate ----------------------------------------------------------------------------

  /**
   * <b>THE PROPERTY THAT MAKES A TEN-MINUTE CRON REASONABLE.</b> Nothing owed and no image train
   * awaiting materialisation costs two indexed reads and a return — and, in particular, no call to
   * qits-configuration and none to qits-ci.
   */
  @Test
  void anIdleEstateIsSweptWithoutTouchingEitherPeer() {
    // A repository and a train that have nothing to do with a polled end: a library release with no
    // adopters at all.
    scanned("qits-eventstream-javalib", RepositoryArchetype.LIBRARY);
    trains.spawn("qits-eventstream-javalib", VERSION, null, WHEN);

    TrainSweep.Result result = sweep.sweep();

    assertFalse(result.asked(), "nothing was owed, so nothing was asked");
    assertEquals(0, result.placed());
    assertEquals(0, result.landed());
    assertTrue(peers.calls.isEmpty(), "an idle sweep makes no HTTP call at all: " + peers.calls);
  }

  // --- materialisation ----------------------------------------------------------------------------

  /**
   * <b>THE NODE A SPAWN CANNOT PLACE.</b> No Dockerfile in the estate says {@code FROM
   * qits/workspace}, so the station opened with nothing on it — and the sweep is what turns
   * qits-configuration's answer into the node the train is actually owed.
   *
   * <p>It also pins the reopening: a train that had arrived because it had nowhere to go is OPEN
   * again once it has somewhere.
   */
  @Test
  void anImageTrainGainsANodeForEveryApplicationThatDeploysTheImage() {
    MtTrain train = anImageRelease(VERSION);
    assertEquals(
        TrainStatus.COMPLETED.name(),
        train.status,
        "nothing pins the image in a manifest, so the spawn had nowhere to send it");
    configurationPins(IMAGE, "2026.904.1", APPLICATION);

    TrainSweep.Result result = sweep.sweep();

    assertEquals(1, result.placed());
    MtTrainNode placed = node(train, APPLICATION);
    // The APPLICATION, not a repository name — the two namespaces meet on this column.
    assertEquals(TrainEndKind.CONFIG_IMAGE_PIN.name(), placed.endKind);
    assertEquals(TrainNodeState.PENDING.name(), placed.state, "the configuration is still behind");
    assertEquals(
        TrainStatus.OPEN.name(),
        store.train(train.id).orElseThrow().status,
        "a completed-empty train that gains a node is open again");
  }

  /** An image nobody deploys is a legitimate answer and places nothing. */
  @Test
  void anImageNoConfigurationDeploysGainsNothing() {
    MtTrain train = anImageRelease(VERSION);
    configurationPins("qits/project-agent", VERSION, "qits-projects");

    TrainSweep.Result result = sweep.sweep();

    assertTrue(result.asked(), "the train was a candidate, so the answer was fetched");
    assertEquals(0, result.placed());
    assertTrue(store.trainNodes(train.id).isEmpty());
  }

  /** Materialisation is idempotent: the second pass over the same answer writes nothing. */
  @Test
  void aSecondSweepPlacesNoSecondNode() {
    MtTrain train = anImageRelease(VERSION);
    configurationPins(IMAGE, "2026.904.1", APPLICATION);

    sweep.sweep();
    TrainSweep.Result again = sweep.sweep();

    assertEquals(0, again.placed());
    assertEquals(1, store.trainNodes(train.id).size());
  }

  // --- the config-pin landing ---------------------------------------------------------------------

  /**
   * <b>The whole point: a pin that has moved to the released version lands the node and the train
   * arrives.</b> Placed and landed in ONE pass, because the answer that materialised the node is the
   * same answer that satisfies it — an image released an hour ago and deployed since must not wait
   * ten minutes for a sweep that learns nothing new.
   */
  @Test
  void aConfigurationAtTheReleasedVersionLandsTheNodeInTheSamePass() {
    MtTrain train = anImageRelease(VERSION);
    configurationPins(IMAGE, VERSION, APPLICATION);

    TrainSweep.Result result = sweep.sweep();

    assertEquals(1, result.placed());
    assertEquals(1, result.landed());
    MtTrainNode landed = node(train, APPLICATION);
    assertEquals(TrainNodeState.LANDED.name(), landed.state);
    // The OBSERVED version, which for a polled end is what the column holds — an application has no
    // release of its own. See MtTrainNode.adoptedVersion.
    assertEquals(VERSION, landed.adoptedVersion);
    // The peer's own moment, never this service's clock.
    assertEquals(Instant.parse("2026-09-05T10:00:00Z"), landed.landedAt);

    MtTrain arrived = store.train(train.id).orElseThrow();
    assertEquals(TrainStatus.COMPLETED.name(), arrived.status);
    assertNotNull(arrived.completedAt);
  }

  /**
   * <b>Inclusive, like every other comparison a train makes.</b> A configuration that skipped past
   * the released version to the one after has still adopted it, and the node this closes is still
   * that train's.
   */
  @Test
  void aConfigurationPastTheReleasedVersionLandsTheNodeToo() {
    MtTrain train = anImageRelease(VERSION);
    configurationPins(IMAGE, "2026.906.1", APPLICATION);

    sweep.sweep();

    assertEquals(TrainNodeState.LANDED.name(), node(train, APPLICATION).state);
  }

  /** And below it is the estate still running the old copy, which is what a train exists to show. */
  @Test
  void aConfigurationBehindTheReleasedVersionHoldsTheNode() {
    MtTrain train = anImageRelease(VERSION);
    configurationPins(IMAGE, "2026.904.1", APPLICATION);

    sweep.sweep();

    MtTrainNode held = node(train, APPLICATION);
    assertEquals(TrainNodeState.PENDING.name(), held.state);
    assertNull(held.landedAt);
    assertEquals(TrainStatus.OPEN.name(), store.train(train.id).orElseThrow().status);
  }

  /**
   * <b>ONE APPLICATION, TWO KEYS, AND THE LOWEST DECIDES.</b> A node is keyed by the application
   * rather than by the configuration entry, so an application that moved one of its two pins has not
   * finished taking the release — something it deploys still runs the old image.
   */
  @Test
  void anApplicationThatMovedOnlyOneOfItsTwoKeysHasNotAdoptedTheRelease() {
    MtTrain train = anImageRelease(VERSION);
    configurationAnswer(
        "{\"generatedAt\":\"2026-09-05T10:00:00Z\",\"pins\":["
            + "{\"image\":\"" + IMAGE + "\",\"version\":\"" + VERSION + "\",\"application\":\""
            + APPLICATION + "\",\"key\":\"env.QITS_WORKSPACE_IMAGE_VERSION\"},"
            + "{\"image\":\"" + IMAGE + "\",\"version\":\"2026.904.1\",\"application\":\""
            + APPLICATION + "\",\"key\":\"env.QITS_WORKSPACE_EDITOR_IMAGE_VERSION\"}]}");

    sweep.sweep();

    assertEquals(1, store.trainNodes(train.id).size(), "two keys, one application, one node");
    assertEquals(TrainNodeState.PENDING.name(), node(train, APPLICATION).state);

    // …and when the second key follows, the node lands.
    configurationAnswer(
        "{\"generatedAt\":\"2026-09-05T11:00:00Z\",\"pins\":["
            + "{\"image\":\"" + IMAGE + "\",\"version\":\"" + VERSION + "\",\"application\":\""
            + APPLICATION + "\",\"key\":\"env.QITS_WORKSPACE_IMAGE_VERSION\"},"
            + "{\"image\":\"" + IMAGE + "\",\"version\":\"" + VERSION + "\",\"application\":\""
            + APPLICATION + "\",\"key\":\"env.QITS_WORKSPACE_EDITOR_IMAGE_VERSION\"}]}");
    sweep.sweep();
    assertEquals(TrainNodeState.LANDED.name(), node(train, APPLICATION).state);
  }

  /** A landed node is never revisited, whatever a later answer says. */
  @Test
  void aLandedNodeIsNotMovedByALaterAnswer() {
    MtTrain train = anImageRelease(VERSION);
    configurationPins(IMAGE, VERSION, APPLICATION);
    sweep.sweep();
    MtTrainNode landed = node(train, APPLICATION);

    // The configuration is rolled back to the old image — which is a fact about today, not about
    // whether this release ever reached the estate.
    configurationPins(IMAGE, "2026.904.1", APPLICATION);
    TrainSweep.Result again = sweep.sweep();

    assertEquals(0, again.landed());
    MtTrainNode unchanged = node(train, APPLICATION);
    assertEquals(TrainNodeState.LANDED.name(), unchanged.state);
    assertEquals(landed.landedAt, unchanged.landedAt);
    assertEquals(landed.adoptedVersion, unchanged.adoptedVersion);
  }

  // --- the daemon landing -------------------------------------------------------------------------

  /** A daemon train's node is the SERVICE that hands the daemon out, and the ladder is its evidence. */
  @Test
  void aLadderAtTheReleasedVersionLandsTheDaemonNode() {
    MtTrain train = aDaemonRelease(VERSION);
    assertEquals(TrainEndKind.DAEMON_PIN.name(), node(train, DAEMON_ADOPTER).endKind);
    ladder(DAEMON, VERSION, "adopted");

    TrainSweep.Result result = sweep.sweep();

    assertEquals(1, result.landed());
    assertEquals(TrainNodeState.LANDED.name(), node(train, DAEMON_ADOPTER).state);
    assertEquals(VERSION, node(train, DAEMON_ADOPTER).adoptedVersion);
    assertEquals(TrainStatus.COMPLETED.name(), store.train(train.id).orElseThrow().status);
  }

  /**
   * <b>{@code configured} counts.</b> A platform that pinned the build by hand is still handing it
   * out; a rule that only accepted {@code adopted} would leave such a train open for ever.
   */
  @Test
  void aConfiguredRungCountsAsMuchAsAnAdoptedOne() {
    MtTrain train = aDaemonRelease(VERSION);
    ladder(DAEMON, VERSION, "configured");

    sweep.sweep();

    assertEquals(TrainNodeState.LANDED.name(), node(train, DAEMON_ADOPTER).state);
  }

  /** {@code none} carries no version at all, so it lands nobody. */
  @Test
  void anEmptyLadderHoldsTheDaemonNode() {
    MtTrain train = aDaemonRelease(VERSION);
    ladder(DAEMON, "", "none");

    sweep.sweep();

    assertEquals(TrainNodeState.PENDING.name(), node(train, DAEMON_ADOPTER).state);
  }

  /** A ladder still on the previous build is the service that has not taken the release. */
  @Test
  void aLadderBehindTheReleasedVersionHoldsTheDaemonNode() {
    MtTrain train = aDaemonRelease(VERSION);
    ladder(DAEMON, "2026.904.1", "adopted");

    sweep.sweep();

    assertEquals(TrainNodeState.PENDING.name(), node(train, DAEMON_ADOPTER).state);
  }

  /**
   * <b>The answer names its own daemon, and a train for a DIFFERENT one is left alone.</b> There is
   * one qits-ci address and one ladder behind it; an answer about somebody else's daemon must not
   * close this journey.
   */
  @Test
  void aLadderThatAnswersForAnotherDaemonLandsNothing() {
    MtTrain train = aDaemonRelease(VERSION);
    ladder("qits-other-daemon", VERSION, "adopted");

    TrainSweep.Result result = sweep.sweep();

    assertEquals(0, result.landed());
    assertEquals(TrainNodeState.PENDING.name(), node(train, DAEMON_ADOPTER).state);
  }

  // --- the peers that are not there -----------------------------------------------------------------

  /**
   * <b>An unreachable peer leaves every node PENDING and writes nothing.</b> Not a landing, not a
   * failure state on the row, and not an exception out of the sweep: the next pass decides on a
   * whole answer or on none.
   */
  @Test
  void anUnreachablePeerHoldsEveryNodeAndTheSweepStillReturns() {
    MtTrain image = anImageRelease(VERSION);
    MtTrain daemon = aDaemonRelease(VERSION);
    peers.answer(
        PeerTarget.CONFIGURATION,
        ConfigPinsClient.PATH,
        FakePeers.Scripted.unreachable("qits-configuration is not there"));
    peers.answer(
        PeerTarget.CI, DaemonPinClient.PATH, FakePeers.Scripted.unreachable("qits-ci is not there"));

    TrainSweep.Result result = sweep.sweep();

    assertTrue(result.asked());
    assertEquals(0, result.placed(), "an unreachable configuration materialises nothing");
    assertEquals(0, result.landed());
    assertTrue(store.trainNodes(image.id).isEmpty());
    assertEquals(TrainNodeState.PENDING.name(), node(daemon, DAEMON_ADOPTER).state);
  }

  /** A refusal is the same outcome as an outage, and it is carried as an error rather than thrown. */
  @Test
  void aPeerThatRefusesIsAnErrorAndNotAnEmptyAnswer() {
    MtTrain train = anImageRelease(VERSION);
    peers.answer(
        PeerTarget.CONFIGURATION,
        ConfigPinsClient.PATH,
        FakePeers.Scripted.status(403, "{\"message\":\"forbidden\"}"));

    TrainSweep.Result result = sweep.sweep();

    assertEquals(0, result.placed());
    assertTrue(store.trainNodes(train.id).isEmpty(), "a 403 is not evidence that nobody deploys it");
    // …and the url that was refused is the shipped one, resolved through the real target.
    assertTrue(
        peers.called(PeerTarget.CONFIGURATION, ConfigPinsClient.PATH),
        "the sweep asked at the configured address: " + peers.calls);
  }

  /**
   * <b>Each peer is asked only when there is something its answer could decide.</b> A daemon train
   * standing alone does not make this service read the image pins, and the other way round.
   */
  @Test
  void aDaemonTrainAloneDoesNotAskQitsConfiguration() {
    aDaemonRelease(VERSION);
    ladder(DAEMON, "2026.904.1", "adopted");

    sweep.sweep();

    assertTrue(peers.called(PeerTarget.CI, DaemonPinClient.PATH));
    assertFalse(
        peers.called(PeerTarget.CONFIGURATION, ConfigPinsClient.PATH),
        "no config-pin end is owed, so qits-configuration is not asked");
  }

  /**
   * <b>Only the NEWEST train of an image repository is materialised.</b> An empty station is never
   * superseded — supersession only overtakes OPEN trains — so without the bound every image release
   * this platform ever made would gain a node from today's configuration, and a version nobody will
   * ever deploy again would arrive.
   */
  @Test
  void anOlderImageReleaseIsNotMaterialisedByTodaysConfiguration() {
    scanned(IMAGE_REPOSITORY, RepositoryArchetype.IMAGE);
    store.upsertArtifact(Ecosystem.DOCKER, IMAGE, "2026.901.1", IMAGE_REPOSITORY, WHEN);
    MtTrain old =
        trains
            .spawn(
                IMAGE_REPOSITORY,
                "2026.901.1",
                new TrainService.ReleasedPackage(Ecosystem.DOCKER, IMAGE),
                WHEN)
            .train();
    store.upsertArtifact(
        Ecosystem.DOCKER, IMAGE, VERSION, IMAGE_REPOSITORY, WHEN.plusSeconds(3600));
    MtTrain newest =
        trains
            .spawn(
                IMAGE_REPOSITORY,
                VERSION,
                new TrainService.ReleasedPackage(Ecosystem.DOCKER, IMAGE),
                WHEN.plusSeconds(3600))
            .train();
    configurationPins(IMAGE, VERSION, APPLICATION);

    sweep.sweep();

    assertTrue(store.trainNodes(old.id).isEmpty(), "last month's release is history");
    assertEquals(1, store.trainNodes(newest.id).size());
  }
}
