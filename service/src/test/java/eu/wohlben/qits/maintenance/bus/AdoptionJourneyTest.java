package eu.wohlben.qits.maintenance.bus;

import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.frame;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.softwareReleasePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.api.InventoryReset;
import eu.wohlben.qits.maintenance.entity.MtTrain;
import eu.wohlben.qits.maintenance.entity.MtTrainNode;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
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
import eu.wohlben.qits.maintenance.train.ConfigPinsClient;
import eu.wohlben.qits.maintenance.train.DaemonPinClient;
import eu.wohlben.qits.maintenance.train.TrainSweep;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * <b>ONE JOURNEY, END TO END: a library releases, a frontend takes it, a service takes the
 * frontend, and three trains arrive.</b>
 *
 * <p>Everything else about the trains is tested a piece at a time — {@code train/TrainSpawnTest}
 * for the membership, {@code train/TrainEvaluationTest} for the evaluation, {@link
 * ReleaseTrainListenerTest} for the frames. This is the one place where all of it runs together, in
 * the order and through the seams a deployment actually uses: <b>real {@code SoftwareRelease} frames
 * into both listeners, the real work queue, a real SBOM fetch through {@link FakePeers}, and a real
 * database.</b>
 *
 * <p><b>Both consumers of every release are driven, because they are two of them.</b> {@link
 * SoftwareReleaseListener} moves {@code mt_latest} and opens the {@code mt_artifact} outbox row whose
 * ingest reads the document; {@link ReleaseTrainListener} opens the station. Nothing coordinates the
 * two, and this journey deliberately drives them in a DIFFERENT ORDER for the frontend (document
 * first) than for the service (station first) — both orders happen on the bus, and the estate has to
 * converge either way.
 *
 * <p><b>And the two journeys that END SOMEWHERE NO EVENT REACHES</b> are here too, at the bottom: a
 * daemon release that finishes on qits-ci's adoption ladder, and an image release that finishes as
 * the runtime pin in qits-configuration. Neither of those last steps is announced by anything, so
 * both are driven the way a deployment drives them — the frames through the listeners, and then
 * {@code TrainSweep} asking. They belong beside the SBOM journey rather than in a suite of their own
 * because they are the same journey with a different last mile, and the whole value of a journey test
 * is that the mile is not tested in isolation.
 *
 * <p><b>It is a {@code @QuarkusTest} rather than a story IT, and that is worth saying out loud.</b>
 * The {@code stories/} suite launches the packaged artifact and drives it over HTTP; a bus frame is
 * not something anything can post to this service from outside, and the trains have no route yet —
 * so there is no seam a launched-process story could drive this through. The journey is therefore
 * driven in-process, at the listener. What that gives up against a story is the network diagram and
 * the packaged wiring; what it keeps is every seam between the frame and the row.
 */
@QuarkusTest
class AdoptionJourneyTest {

  /** The library at the bottom of the chain, and the package it publishes. */
  private static final String LIBRARY = "qits-ui-components-jslib";
  private static final String LIBRARY_PACKAGE = "@qits/ui-components";
  private static final String LIBRARY_VERSION = "2026.905.1";

  /** The frontend in the middle: it takes the library and publishes a bundle of its own. */
  private static final String FRONTEND = "qits-ci-frontend";
  private static final String FRONTEND_PACKAGE = "@qits/ci-spa";
  private static final String FRONTEND_VERSION = "2026.905.2";

  /** And the service at the top, which embeds the bundle and is itself adopted by nobody. */
  private static final String SERVICE = "qits-ci-service";
  private static final String SERVICE_PACKAGE = "eu.wohlben.qits:qits-ci";
  private static final String SERVICE_VERSION = "2026.905.3";

  /** The daemon, and the service that hands it out — {@code TrainService.DAEMON_ADOPTERS}. */
  private static final String DAEMON = "qits-ci-daemon";
  private static final String DAEMON_ADOPTER = "qits-ci";
  private static final String DAEMON_VERSION = "2026.905.4";

  /** The image repository, its image, and the application whose deployment config deploys it. */
  private static final String IMAGE_REPOSITORY = "qits-workspace-oci";
  private static final String IMAGE = "qits/workspace";
  private static final String IMAGE_APPLICATION = "qits-workspaces";
  private static final String IMAGE_VERSION = "2026.905.5";

  @Inject SoftwareReleaseListener releases;

  @Inject ReleaseTrainListener trains;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject WorkQueue queue;

  @Inject InventoryReset reset;

  /** The polled half of the journey — see the two tests at the bottom of this class. */
  @Inject TrainSweep sweep;

  @BeforeEach
  void anEstateOfThreeRepositories() {
    reset.clear();
    peers.reset();
    scanned(LIBRARY, RepositoryArchetype.LIBRARY);
    scanned(FRONTEND, RepositoryArchetype.FRONTEND, npm(LIBRARY_PACKAGE, "2026.904.9"));
    scanned(SERVICE, RepositoryArchetype.SERVICE, npm(FRONTEND_PACKAGE, "2026.904.9"));
  }

  private void scanned(String repository, RepositoryArchetype archetype, ParsedPin... pins) {
    store.replaceInventory(
        repository,
        "qits",
        null,
        archetype.name(),
        "main",
        RepositoryStatus.OK,
        "sha",
        null,
        List.of(pins),
        List.of(),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
  }

  private static ParsedPin npm(String name, String version) {
    return ParsedPin.of(Ecosystem.NPM, "package.json", name, version, null, "dependency:" + name);
  }

  /** The document qits-artifacts answers for one release, naming the one thing it contains. */
  private void sbomOf(
      String packageType,
      String name,
      String version,
      String containsPurl,
      String containsName,
      String containsVersion) {
    String document =
        """
        {"bomFormat":"CycloneDX","specVersion":"1.5","version":1,
         "metadata":{"component":{"bom-ref":"self","type":"library",
                                  "name":"%s","version":"%s"}},
         "components":[{"bom-ref":"c-1","type":"library","name":"%s","version":"%s",
                        "purl":"%s"}],
         "dependencies":[{"ref":"self","dependsOn":["c-1"]},{"ref":"c-1","dependsOn":[]}]}
        """
            .formatted(name, version, containsName, containsVersion, containsPurl);
    peers.answer(
        PeerTarget.ARTIFACTS_SBOM,
        "/artifacts/sboms/" + packageType + "/" + name + "/-/" + version,
        FakePeers.Scripted.ok(document));
  }

  /**
   * One release, offered to both consumers — the document side first or the station side first.
   *
   * <p>The barrier between them is not tidiness: the document is fetched on the single worker
   * thread, so "the SBOM has been read" is only a fact once the queue has drained, and a test that
   * did not wait would be asserting on whichever half of the race it happened to catch.
   */
  private void released(
      boolean documentFirst, String repository, String packageType, String packageName,
      String version) {
    String payload = softwareReleasePayload(repository, packageType, packageName, version);
    if (documentFirst) {
      releases.onFrame(frame("SoftwareRelease", payload));
      drained();
      trains.onFrame(frame("SoftwareRelease", payload));
    } else {
      trains.onFrame(frame("SoftwareRelease", payload));
      drained();
      releases.onFrame(frame("SoftwareRelease", payload));
    }
    drained();
  }

  private void drained() {
    assertTrue(queue.awaitIdle(Duration.ofSeconds(30)), "the ingest queue drained");
  }

  private MtTrain train(String repository, String version) {
    return store
        .train(repository, version)
        .orElseThrow(() -> new AssertionError("no station was opened for " + repository));
  }

  private MtTrainNode node(MtTrain train, String consumer) {
    return store.trainNodes(train.id).stream()
        .filter(node -> consumer.equals(node.consumer))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError(consumer + " is not on the train of " + train.repository));
  }

  private void theDocumentsOfThisJourney() {
    // The frontend's bundle names the library; the service's release names the bundle. Nothing
    // names the service, which is what makes its station a journey of length zero.
    sbomOf(
        "npm",
        FRONTEND_PACKAGE,
        FRONTEND_VERSION,
        "pkg:npm/%40qits%2Fui-components@" + LIBRARY_VERSION,
        LIBRARY_PACKAGE,
        LIBRARY_VERSION);
    sbomOf(
        "maven",
        SERVICE_PACKAGE,
        SERVICE_VERSION,
        "pkg:npm/%40qits%2Fci-spa@" + FRONTEND_VERSION,
        FRONTEND_PACKAGE,
        FRONTEND_VERSION);
  }

  /**
   * <b>THE WHOLE JOURNEY.</b> Three releases, six frames, two documents — and at the end of it a
   * question that has no answer anywhere else on this platform is a row: the library's 2026.905.1
   * reached the estate, and here is when.
   */
  @Test
  @Timeout(180)
  void aLibraryReleaseTravelsThroughTheFrontendAndTheServiceAndEveryTrainArrives() {
    theDocumentsOfThisJourney();

    // 1. THE LIBRARY RELEASES. Its own document is a 404, which is the ordinary answer from that
    //    route and costs nothing here: what the station needs off the artifact row is the
    //    coordinate, and the row is written whether the document reads or not.
    released(true, LIBRARY, "npm", LIBRARY_PACKAGE, LIBRARY_VERSION);

    MtTrain library = train(LIBRARY, LIBRARY_VERSION);
    assertEquals(TrainStatus.OPEN.name(), library.status);
    assertEquals(
        List.of(FRONTEND),
        store.trainNodes(library.id).stream().map(node -> node.consumer).toList(),
        "the frontend pins the library, so it is who the release is owed by");
    assertEquals(TrainNodeState.PENDING.name(), node(library, FRONTEND).state);

    // 2. THE FRONTEND RELEASES, DOCUMENT FIRST. The adoption is recorded before the station of the
    //    frontend's own release exists, so its child link is left for the spawn behind it.
    released(true, FRONTEND, "npm", FRONTEND_PACKAGE, FRONTEND_VERSION);

    MtTrain frontend = train(FRONTEND, FRONTEND_VERSION);
    assertEquals(TrainStatus.OPEN.name(), frontend.status);
    assertEquals(
        List.of(SERVICE),
        store.trainNodes(frontend.id).stream().map(node -> node.consumer).toList());

    MtTrainNode adopted = node(library, FRONTEND);
    assertEquals(TrainNodeState.ADOPTED.name(), adopted.state, "the frontend's bundle ships it");
    // THE FRONTEND'S OWN VERSION, not the library version it took — this column is half of the
    // address the adopting release's request is looked up by, and that resolver matches the
    // CONSUMER's releases.
    assertEquals(FRONTEND_VERSION, adopted.adoptedVersion);
    assertEquals(frontend.id, adopted.childTrainId, "…and the spawn behind the ingest linked it");

    // 3. THE SERVICE RELEASES, STATION FIRST — the other ordering, in the same journey. Nobody pins
    //    the service, so its train is COMPLETED at creation and the cascade runs two levels.
    released(false, SERVICE, "maven", SERVICE_PACKAGE, SERVICE_VERSION);

    MtTrain service = train(SERVICE, SERVICE_VERSION);
    assertEquals(
        TrainStatus.COMPLETED.name(), service.status, "a release nobody was expected to adopt");

    assertEquals(TrainNodeState.LANDED.name(), node(frontend, SERVICE).state);
    assertEquals(
        SERVICE_VERSION,
        node(frontend, SERVICE).adoptedVersion,
        "each node names the adopting repository's OWN release, one step up the chain each time");
    assertEquals(TrainStatus.COMPLETED.name(), store.train(frontend.id).orElseThrow().status);

    assertEquals(
        TrainNodeState.LANDED.name(),
        node(library, FRONTEND).state,
        "and the completion cascaded one further up, which is the whole point of the link");
    MtTrain arrived = store.train(library.id).orElseThrow();
    assertEquals(TrainStatus.COMPLETED.name(), arrived.status);
    assertNotNull(arrived.completedAt);
  }

  /**
   * <b>The same journey, redelivered.</b> The bus is at-least-once and a catch-up re-offers whatever
   * was in flight, so every frame above can arrive twice — and the second pass changes nothing: no
   * second station, no second node, no adoption re-stamped and no train re-completed.
   */
  @Test
  @Timeout(180)
  void aRedeliveryOfEveryFrameInTheJourneyChangesNothing() {
    theDocumentsOfThisJourney();

    released(true, LIBRARY, "npm", LIBRARY_PACKAGE, LIBRARY_VERSION);
    released(true, FRONTEND, "npm", FRONTEND_PACKAGE, FRONTEND_VERSION);
    released(false, SERVICE, "maven", SERVICE_PACKAGE, SERVICE_VERSION);

    MtTrain library = train(LIBRARY, LIBRARY_VERSION);
    MtTrainNode landed = node(library, FRONTEND);
    Instant completedAt = store.train(library.id).orElseThrow().completedAt;
    assertEquals(TrainNodeState.LANDED.name(), landed.state);

    released(true, LIBRARY, "npm", LIBRARY_PACKAGE, LIBRARY_VERSION);
    released(true, FRONTEND, "npm", FRONTEND_PACKAGE, FRONTEND_VERSION);
    released(false, SERVICE, "maven", SERVICE_PACKAGE, SERVICE_VERSION);

    assertEquals(1, store.trains(LIBRARY, 20).size(), "one station per released version");
    assertEquals(1, store.trainNodes(library.id).size());
    MtTrainNode again = node(library, FRONTEND);
    assertEquals(landed.state, again.state);
    assertEquals(landed.adoptedVersion, again.adoptedVersion);
    assertEquals(landed.adoptedAt, again.adoptedAt);
    assertEquals(landed.landedAt, again.landedAt);
    assertEquals(completedAt, store.train(library.id).orElseThrow().completedAt);
  }

  // --- the two journeys no event can finish ---------------------------------------------------

  /**
   * <b>A DAEMON RELEASE, END TO END: qits-ci-daemon publishes, and the journey ends on qits-ci's
   * adoption ladder.</b>
   *
   * <p>Nothing pins a daemon in a manifest and nothing announces that a service started handing out
   * a new build, so this is the one journey where the last step is a QUESTION rather than an event —
   * the frames open the station, and {@code TrainSweep} is what closes it. Both halves are driven
   * here for the same reason the three-release journey above drives both listeners: the seam between
   * them is where a feature like this actually breaks.
   */
  @Test
  @Timeout(180)
  void aDaemonReleaseEndsAtTheLadderOfTheServiceThatHandsItOut() {
    scanned(DAEMON, RepositoryArchetype.DAEMON);

    // 1. THE RELEASE. `daemon` is a package type this service's inventory does not hold — no
    //    coordinate, no artifact row — and the station is opened anyway, because a train is not an
    //    inventory row.
    released(true, DAEMON, "daemon", DAEMON, DAEMON_VERSION);

    MtTrain train = train(DAEMON, DAEMON_VERSION);
    assertEquals(TrainStatus.OPEN.name(), train.status);
    MtTrainNode owed = node(train, DAEMON_ADOPTER);
    assertEquals(TrainEndKind.DAEMON_PIN.name(), owed.endKind);
    assertEquals(TrainNodeState.PENDING.name(), owed.state);

    // 2. THE LADDER IS STILL ON YESTERDAY'S BUILD. A sweep asks and writes nothing at all.
    ladder("2026.905.1", "adopted");
    assertEquals(0, sweep.sweep().landed());
    assertEquals(TrainNodeState.PENDING.name(), node(train, DAEMON_ADOPTER).state);

    // 3. THE RUNG IS PROVEN. qits-ci hands out the new build, and the only way this service can
    //    learn that is the question the sweep asks.
    ladder(DAEMON_VERSION, "adopted");
    assertEquals(1, sweep.sweep().landed());

    assertEquals(TrainNodeState.LANDED.name(), node(train, DAEMON_ADOPTER).state);
    assertEquals(DAEMON_VERSION, node(train, DAEMON_ADOPTER).adoptedVersion);
    MtTrain arrived = store.train(train.id).orElseThrow();
    assertEquals(TrainStatus.COMPLETED.name(), arrived.status, "the daemon reached the estate");
    assertNotNull(arrived.completedAt);
  }

  /**
   * <b>AN IMAGE RELEASE, END TO END: qits/workspace publishes, and the journey ends at the runtime
   * pin in qits-configuration.</b>
   *
   * <p>This is the journey whose FIRST step is missing as well as its last. Nothing in the estate
   * says {@code FROM qits/workspace}, so the spawn has nobody to place and closes the station as a
   * journey of length zero — and the sweep both materialises the node the release is really owed and
   * closes it, from the one answer qits-configuration gives.
   */
  @Test
  @Timeout(180)
  void anImageReleaseEndsAtTheConfigurationThatDeploysIt() {
    scanned(IMAGE_REPOSITORY, RepositoryArchetype.IMAGE);

    // 1. THE RELEASE. A docker frame, so this half of it IS an inventory row — that row is where the
    //    image name the pins are joined on comes from.
    released(true, IMAGE_REPOSITORY, "docker", IMAGE, IMAGE_VERSION);

    MtTrain train = train(IMAGE_REPOSITORY, IMAGE_VERSION);
    assertEquals(
        TrainStatus.COMPLETED.name(),
        train.status,
        "no Dockerfile in the estate pins the image, so the spawn had nowhere to send it");
    assertTrue(store.trainNodes(train.id).isEmpty());

    // 2. THE CONFIGURATION IS STILL DEPLOYING LAST WEEK'S IMAGE. The sweep places the node the
    //    spawn could not — and the station, which had arrived because it had nowhere to go, is open
    //    again now that it has somewhere.
    imagePins("2026.904.1");
    assertEquals(1, sweep.sweep().placed());

    MtTrainNode owed = node(train, IMAGE_APPLICATION);
    assertEquals(TrainEndKind.CONFIG_IMAGE_PIN.name(), owed.endKind);
    assertEquals(TrainNodeState.PENDING.name(), owed.state);
    assertEquals(TrainStatus.OPEN.name(), store.train(train.id).orElseThrow().status);

    // 3. THE DEPLOYMENT MOVES. Nobody announces it; the next sweep is what finds out.
    imagePins(IMAGE_VERSION);
    assertEquals(1, sweep.sweep().landed());

    MtTrainNode landed = node(train, IMAGE_APPLICATION);
    assertEquals(TrainNodeState.LANDED.name(), landed.state);
    assertEquals(IMAGE_VERSION, landed.adoptedVersion, "the version OBSERVED, for a polled end");
    MtTrain arrived = store.train(train.id).orElseThrow();
    assertEquals(TrainStatus.COMPLETED.name(), arrived.status);
    assertNotNull(arrived.completedAt);
  }

  /** What qits-ci says it hands out. */
  private void ladder(String version, String source) {
    peers.answer(
        PeerTarget.CI,
        DaemonPinClient.PATH,
        FakePeers.Scripted.ok(
            "{\"daemonName\":\"" + DAEMON + "\",\"daemonVersion\":\"" + version
                + "\",\"previousDaemonVersion\":\"\",\"source\":\"" + source + "\"}"));
  }

  /** What qits-configuration says the estate deploys. */
  private void imagePins(String version) {
    peers.answer(
        PeerTarget.CONFIGURATION,
        ConfigPinsClient.PATH,
        FakePeers.Scripted.ok(
            "{\"generatedAt\":\"2026-09-05T10:00:00Z\",\"pins\":[{\"image\":\"" + IMAGE
                + "\",\"version\":\"" + version + "\",\"application\":\"" + IMAGE_APPLICATION
                + "\",\"key\":\"env.QITS_WORKSPACE_IMAGE_VERSION\"}]}"));
  }
}
