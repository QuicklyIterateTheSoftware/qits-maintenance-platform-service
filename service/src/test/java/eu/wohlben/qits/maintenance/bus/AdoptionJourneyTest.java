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
import eu.wohlben.qits.maintenance.model.TrainNodeState;
import eu.wohlben.qits.maintenance.model.TrainStatus;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
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

  @Inject SoftwareReleaseListener releases;

  @Inject ReleaseTrainListener trains;

  @Inject MaintenanceStore store;

  @Inject FakePeers peers;

  @Inject WorkQueue queue;

  @Inject InventoryReset reset;

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
}
