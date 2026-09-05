package eu.wohlben.qits.maintenance.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The spawn, against a real PostgreSQL the suite spawns itself.
 *
 * <p><b>Every fixture name carries a uuid.</b> The suite shares one database across the class and
 * this module has no reset helper, so isolation is by naming rather than by truncation — the same
 * rule {@code MaintenanceStoreTest} follows. It also means the cross-cutting reads (
 * {@code configPinCandidates} walks every open train in the schema) are asserted with
 * {@code contains} rather than with equality.
 *
 * <p><b>The moments are literals, not {@code Instant.now()}.</b> {@code created_at} is the key
 * supersession is decided on, so a test that let the clock supply it would be asserting on whichever
 * order the rows happened to be written in — which is exactly the bug the column exists to prevent.
 */
@QuarkusTest
class TrainSpawnTest {

  @Inject TrainService trains;

  @Inject MaintenanceStore store;

  private static final Instant NOON = Instant.parse("2026-09-05T12:00:00Z");
  private static final Instant AFTERNOON = Instant.parse("2026-09-05T15:00:00Z");
  private static final Instant EVENING = Instant.parse("2026-09-05T19:00:00Z");

  private static String named(String what) {
    return what + "-" + UUID.randomUUID();
  }

  /** A repository as a scan left it: an archetype and whatever pins it holds. */
  private void scanned(String repository, String archetype, ParsedPin... pins) {
    scanned(repository, archetype, candidate -> PinKind.INTERNAL, pins);
  }

  private void scanned(
      String repository,
      String archetype,
      Function<ParsedPin, PinKind> kindOf,
      ParsedPin... pins) {
    store.replaceInventory(
        repository,
        "qits",
        null,
        archetype,
        "main",
        RepositoryStatus.OK,
        "sha",
        null,
        List.of(pins),
        List.of(),
        GroupSource.DEFAULT,
        kindOf,
        NOON);
  }

  private static ParsedPin pin(Ecosystem ecosystem, String name, String version) {
    return ParsedPin.of(ecosystem, "pom.xml", name, version, null, "dependency:" + name);
  }

  /** A released artifact of {@code repository} whose bill of materials names {@code dependency}. */
  private void shipsACopyOf(String repository, String artifact, Ecosystem ecosystem, String dependency) {
    UUID id = store.upsertArtifact(ecosystem, artifact, "1.0.0", repository, NOON);
    store.replaceGraph(
        id,
        List.of(
            new ParsedSbom.Component(
                "ref-" + dependency, "pkg:test/" + dependency, ecosystem, dependency, "1.0.0", true)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        NOON);
  }

  private static TrainService.ReleasedPackage maven(String name) {
    return new TrainService.ReleasedPackage(Ecosystem.MAVEN, name);
  }

  private List<String> consumersOf(MtTrain train) {
    return store.trainNodes(train.id).stream().map(node -> node.consumer).toList();
  }

  // --- the degenerate train ---------------------------------------------------------------------

  /**
   * <b>A release nobody was expected to adopt still gets a station, and it arrives the instant it
   * leaves.</b> A train that is absent is indistinguishable from a release this service never heard
   * about, which is the one thing a log of releases must never be ambiguous about.
   */
  @Test
  void aReleaseNobodyPinsArrivesTheInstantItLeaves() {
    String library = named("lonely");
    scanned(library, RepositoryArchetype.LIBRARY.name());

    MtTrain train =
        trains.spawn(library, "1.0.0", maven(named("g:nobody-pins-this")), NOON).train();

    assertEquals(TrainStatus.COMPLETED.name(), train.status);
    assertEquals(NOON, train.completedAt);
    assertEquals(NOON, train.createdAt, "the frame's moment, never the clock");
    assertTrue(store.trainNodes(train.id).isEmpty());
  }

  /** And so does a docs-only release, which names no coordinate at all. */
  @Test
  void aDocsOnlyReleaseIsAJourneyOfLengthZeroAndStillAStation() {
    String service = named("docs-only");
    scanned(service, RepositoryArchetype.SERVICE.name());

    MtTrain train = trains.spawn(service, "2026.905.1", null, NOON).train();

    assertEquals(TrainStatus.COMPLETED.name(), train.status);
    assertTrue(store.train(service, "2026.905.1").isPresent());
  }

  // --- the membership ---------------------------------------------------------------------------

  /**
   * <b>The two sources are a UNION and neither is derivable from the other.</b> A pin is a line
   * somebody wrote and can edit; an SBOM component is a copy an artifact actually ships, transitives
   * included. A train that took only the first would miss everyone carrying the library about
   * without declaring it; only the second, and it would miss everyone who declared it and has not
   * released since.
   */
  @Test
  void theExpectedAdoptersAreThePinHoldersAndTheSbomDependentsTogether() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String declares = named("declares");
    String ships = named("ships");

    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(declares, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.MAVEN, coordinate, "0.9.0"));
    scanned(ships, RepositoryArchetype.SERVICE.name());
    shipsACopyOf(ships, named("eu.wohlben.qits:shipper"), Ecosystem.MAVEN, coordinate);

    MtTrain train = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();

    assertEquals(TrainStatus.OPEN.name(), train.status);
    assertEquals(List.of(declares, ships).stream().sorted().toList(), consumersOf(train));
    for (MtTrainNode node : store.trainNodes(train.id)) {
      assertEquals(TrainNodeState.PENDING.name(), node.state, "nothing is adopted at spawn");
      assertEquals(TrainEndKind.LINKED.name(), node.endKind);
      assertEquals(RepositoryArchetype.SERVICE.name(), node.archetype, "copied at spawn");
    }
  }

  /** One repository holding two pins on one coordinate is one adopter, not two. */
  @Test
  void anAdopterIsDeduplicatedAcrossItsManifestsAndAcrossBothSources() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");

    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(
        consumer,
        RepositoryArchetype.SERVICE.name(),
        ParsedPin.of(Ecosystem.MAVEN, "pom.xml", coordinate, "0.9.0", null, "dependency:a"),
        ParsedPin.of(Ecosystem.MAVEN, "service/pom.xml", coordinate, "0.9.0", null, "dependency:b"));
    shipsACopyOf(consumer, named("eu.wohlben.qits:consumed"), Ecosystem.MAVEN, coordinate);

    MtTrain train = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();

    assertEquals(List.of(consumer), consumersOf(train));
  }

  /** Its pin on its own artifact is REACTOR and its bill of materials names itself. */
  @Test
  void theReleasingRepositoryIsNeverItsOwnAdopter() {
    String library = named("self");
    String coordinate = named("eu.wohlben.qits:self");

    scanned(library, RepositoryArchetype.LIBRARY.name(), pin(Ecosystem.MAVEN, coordinate, "0.9.0"));
    shipsACopyOf(library, named("eu.wohlben.qits:selfshipper"), Ecosystem.MAVEN, coordinate);

    MtTrain train = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();

    assertEquals(TrainStatus.COMPLETED.name(), train.status);
    assertTrue(consumersOf(train).isEmpty());
  }

  /** EXTERNAL, REACTOR and UNRESOLVED pins have no line this platform's release can move. */
  @Test
  void onlyAnInternalPinIsAnExpectedAdoption() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String external = named("thinks-it-is-external");

    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(
        external,
        RepositoryArchetype.SERVICE.name(),
        candidate -> PinKind.EXTERNAL,
        pin(Ecosystem.MAVEN, coordinate, "0.9.0"));

    MtTrain train = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();

    assertTrue(consumersOf(train).isEmpty());
  }

  /**
   * <b>THE WRAPPER IS NOT ON EVERY TRAIN.</b> qits-qits pins every submodule as a gitlink, so a
   * derivation that looked at GITLINK would put it on the train of every release this platform
   * makes — and its gitlinks are banked in bulk by its own release, which is not adoption. The
   * fixture makes the ecosystem the ONLY thing separating the two pins, so the exclusion cannot pass
   * by accident.
   */
  @Test
  void aGitlinkPinIsNeverATrainsConcern() {
    String image = named("qits-some-image");
    String wrapper = named("qits-qits");

    scanned(image, RepositoryArchetype.IMAGE.name());
    scanned(
        wrapper,
        RepositoryArchetype.PROJECT.name(),
        ParsedPin.of(Ecosystem.GITLINK, ".gitmodules", image, "abc1234", null, "submodule:" + image));

    MtTrain train =
        trains
            .spawn(image, "1.0.0", new TrainService.ReleasedPackage(Ecosystem.DOCKER, image), NOON)
            .train();

    assertTrue(
        consumersOf(train).isEmpty(),
        "the same name under a different ecosystem is a different fact");
  }

  /** A word the catalog answered that this build does not know costs a node its typed reading only. */
  @Test
  void anUnknownArchetypeIsPlainLinkedAndIsStoredVerbatim() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String odd = named("odd");

    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(odd, "QUANTUM_MESH", pin(Ecosystem.MAVEN, coordinate, "0.9.0"));

    MtTrain train = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();

    MtTrainNode node = store.trainNodes(train.id).get(0);
    assertEquals(odd, node.consumer);
    assertEquals("QUANTUM_MESH", node.archetype);
    assertEquals(TrainEndKind.LINKED.name(), node.endKind);
  }

  /** A repository the catalog has not classified is placed all the same. */
  @Test
  void aConsumerWithNoArchetypeIsStillAnExpectedAdopter() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String unclassified = named("unclassified");

    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(unclassified, null, pin(Ecosystem.MAVEN, coordinate, "0.9.0"));

    MtTrain train = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();

    MtTrainNode node = store.trainNodes(train.id).get(0);
    assertEquals(unclassified, node.consumer);
    assertNull(node.archetype);
  }

  // --- the daemon end ----------------------------------------------------------------------------

  /**
   * A daemon is pinned by nothing: it is distributed by a service, which records the build it hands
   * out. {@code TrainService.DAEMON_ADOPTERS} is where that fact is written down.
   */
  @Test
  void aDaemonReleaseCarriesThePinNodeOfTheServiceThatHandsItOut() {
    String daemon = "qits-ci-daemon";
    String adopter = "qits-ci";
    scanned(daemon, RepositoryArchetype.DAEMON.name());
    scanned(adopter, RepositoryArchetype.SERVICE.name());

    MtTrain train = trains.spawn(daemon, "2026.905.1", null, NOON).train();

    assertEquals(TrainStatus.OPEN.name(), train.status);
    List<MtTrainNode> nodes = store.trainNodes(train.id);
    assertEquals(1, nodes.size());
    assertEquals(adopter, nodes.get(0).consumer);
    assertEquals(TrainEndKind.DAEMON_PIN.name(), nodes.get(0).endKind);
  }

  /**
   * And the two ends of one consumer are two nodes, because they settle independently: a service
   * that also pins the daemon's image in a Dockerfile owes the train a manifest bump AND a daemon
   * pin, and closing one says nothing about the other.
   */
  @Test
  void oneConsumerCanOweATrainTwoEndsAtOnce() {
    String daemon = "qits-ci-daemon";
    String adopter = "qits-ci";
    String image = named("qits/ci-daemon");
    scanned(daemon, RepositoryArchetype.DAEMON.name());
    scanned(adopter, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.DOCKER, image, "0.9.0"));

    MtTrain train =
        trains
            .spawn(
                daemon, "2026.905.2", new TrainService.ReleasedPackage(Ecosystem.DOCKER, image), NOON)
            .train();

    assertEquals(
        List.of(TrainEndKind.DAEMON_PIN.name(), TrainEndKind.LINKED.name()),
        store.trainNodes(train.id).stream().map(node -> node.endKind).sorted().toList());
  }

  // --- idempotence -------------------------------------------------------------------------------

  /** The ordinary at-least-once redelivery: a read and a return. */
  @Test
  void aRedeliveredFrameSettlesOntoTheStationItAlreadyOpened() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(consumer, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.MAVEN, coordinate, "0.9.0"));

    MaintenanceStore.TrainSpawn first = trains.spawn(library, "1.0.0", maven(coordinate), NOON);
    MaintenanceStore.TrainSpawn again = trains.spawn(library, "1.0.0", maven(coordinate), NOON);

    assertTrue(first.created());
    assertFalse(again.created());
    assertEquals(first.train().id, again.train().id);
    assertEquals(1, store.trainNodes(first.train().id).size());
  }

  /**
   * <b>A SIBLING FRAME MUST NOT TOUCH NODE STATE.</b> Four packages of one release arrive as four
   * frames; the second, third and fourth are not new information about any node, and a re-derivation
   * behind them would reset an adoption somebody's evaluation had already recorded.
   */
  @Test
  void aSiblingPackageOfOneReleaseSettlesWithoutDisturbingTheNodesAlreadyThere() {
    String service = named("svc");
    String jar = named("eu.wohlben.qits:svc");
    String image = named("qits/svc");
    String consumer = named("consumer");
    scanned(service, RepositoryArchetype.SERVICE.name());
    scanned(consumer, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.MAVEN, jar, "0.9.0"));

    MtTrain train = trains.spawn(service, "2026.905.1", maven(jar), NOON).train();
    MtTrainNode node = store.trainNodes(train.id).get(0);
    store.nodeAdopted(node.id, "2026.905.1", null, AFTERNOON);

    trains.spawn(
        service, "2026.905.1", new TrainService.ReleasedPackage(Ecosystem.DOCKER, image), NOON);

    List<MtTrainNode> after = store.trainNodes(train.id);
    assertEquals(1, after.size(), "a sibling frame re-derives nothing");
    assertEquals(TrainNodeState.ADOPTED.name(), after.get(0).state, "and disturbs nothing");
  }

  /**
   * <b>The one exception, and the reason it exists.</b> The {@code docs} frame of a release names no
   * coordinate, so a spawn behind it has nothing to look adopters up by — and the four frames of one
   * release arrive in whatever order the bus offers them. If an empty station were as untouchable as
   * a populated one, a whole release's train would read as "nobody was expected to adopt it" on a
   * coin flip. An empty train has no state to protect, so the frame that does know fills it in.
   */
  @Test
  void aStationOpenedEmptyByTheDocsFrameIsFilledInByTheFrameThatKnows() {
    String service = named("svc");
    String jar = named("eu.wohlben.qits:svc");
    String consumer = named("consumer");
    scanned(service, RepositoryArchetype.SERVICE.name());
    scanned(consumer, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.MAVEN, jar, "0.9.0"));

    MtTrain opened = trains.spawn(service, "2026.905.1", null, NOON).train();
    assertEquals(TrainStatus.COMPLETED.name(), opened.status);

    MaintenanceStore.TrainSpawn filled = trains.spawn(service, "2026.905.1", maven(jar), NOON);

    assertEquals(opened.id, filled.train().id, "one station, not two");
    assertEquals(List.of(consumer), consumersOf(filled.train()));
    MtTrain reread = store.train(filled.train().id).orElseThrow();
    assertEquals(TrainStatus.OPEN.name(), reread.status, "it had arrived because it had nowhere to go");
    assertNull(reread.completedAt);
  }

  // --- supersession ------------------------------------------------------------------------------

  /** Forwards: the estate should be adopting the newer version. */
  @Test
  void aNewerReleaseSupersedesTheOpenTrainOfTheSameRepository() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(consumer, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.MAVEN, coordinate, "0.9.0"));

    MtTrain first = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();
    MtTrain second = trains.spawn(library, "2.0.0", maven(coordinate), AFTERNOON).train();

    MtTrain overtaken = store.train(first.id).orElseThrow();
    assertEquals(TrainStatus.SUPERSEDED.name(), overtaken.status);
    assertEquals(second.id, overtaken.supersededBy);
    assertEquals(TrainStatus.OPEN.name(), store.train(second.id).orElseThrow().status);
    assertEquals(
        1,
        store.trainNodes(first.id).size(),
        "a superseded train keeps its nodes: a consumer that takes 1.0.0 still adopted it");
  }

  /** Backwards, which is the arm a catch-up produces: the newer train is already there. */
  @Test
  void aTrainSpawnedBehindANewerOneIsBornSuperseded() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(consumer, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.MAVEN, coordinate, "0.9.0"));

    MtTrain newest = trains.spawn(library, "2.0.0", maven(coordinate), AFTERNOON).train();
    MtTrain caughtUp = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();

    assertEquals(TrainStatus.SUPERSEDED.name(), caughtUp.status);
    assertEquals(newest.id, caughtUp.supersededBy);
    assertEquals(TrainStatus.OPEN.name(), store.train(newest.id).orElseThrow().status);
  }

  /**
   * <b>And it converges however the replay is ordered.</b> Three releases handled 3, 1, 2 leave the
   * first two superseded BY THE THIRD — not by whichever happened to be written last, which is what
   * a clock stamp or a self-referencing supersession would produce.
   */
  @Test
  void anOutOfOrderCatchUpConvergesOnTheNewestRelease() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(consumer, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.MAVEN, coordinate, "0.9.0"));

    MtTrain third = trains.spawn(library, "3.0.0", maven(coordinate), EVENING).train();
    MtTrain first = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();
    MtTrain second = trains.spawn(library, "2.0.0", maven(coordinate), AFTERNOON).train();

    assertEquals(third.id, store.train(first.id).orElseThrow().supersededBy);
    assertEquals(third.id, store.train(second.id).orElseThrow().supersededBy);
    assertEquals(
        List.of(third.id),
        store.openTrains(library).stream().map(train -> train.id).toList(),
        "exactly one train of a repository is open");
  }

  /** Another repository's release is not this repository's business. */
  @Test
  void supersessionIsPerRepositoryAndNeverAcrossTheEstate() {
    String one = named("one");
    String other = named("other");
    String coordinate = named("eu.wohlben.qits:shared");
    String consumer = named("consumer");
    scanned(one, RepositoryArchetype.LIBRARY.name());
    scanned(other, RepositoryArchetype.LIBRARY.name());
    scanned(consumer, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.MAVEN, coordinate, "0.9.0"));

    MtTrain mine = trains.spawn(one, "1.0.0", maven(coordinate), NOON).train();
    trains.spawn(other, "9.9.9", maven(coordinate), EVENING);

    assertEquals(TrainStatus.OPEN.name(), store.train(mine.id).orElseThrow().status);
  }

  // --- the config-pin seam -------------------------------------------------------------------------

  /**
   * <b>An IMAGE release's config side is ABSENT at spawn and that is the design.</b> Those pins live
   * in qits-configuration and can only be read over HTTP; a spawn runs inside the bus's claim
   * transaction, where an outbound call turns a slow peer into an event redelivered for ever. The
   * sweep places them afterwards, through a key that makes a second pass a no-op.
   */
  @Test
  void theConfigPinNodesOfAnImageReleaseArePlacedByTheSweepAndNotBySpawn() {
    String image = named("qits-base-image");
    String coordinate = named("qits/base");
    String builder = named("builder");
    scanned(image, RepositoryArchetype.IMAGE.name());
    scanned(builder, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.DOCKER, coordinate, "0.9.0"));

    MtTrain train =
        trains
            .spawn(
                image, "1.0.0", new TrainService.ReleasedPackage(Ecosystem.DOCKER, coordinate), NOON)
            .train();

    assertEquals(
        List.of(TrainEndKind.LINKED.name()),
        store.trainNodes(train.id).stream().map(node -> node.endKind).toList(),
        "no HTTP read happens inside a claim transaction");
    assertTrue(
        trains.configPinCandidates().stream().anyMatch(candidate -> candidate.id.equals(train.id)),
        "and the sweep can find it without a flag column");

    assertEquals(2, trains.recordConfigImagePins(train.id, List.of("qits-ci", "qits-workspaces")));
    assertEquals(
        0,
        trains.recordConfigImagePins(train.id, List.of("qits-ci", "qits-workspaces")),
        "a second sweep over the same train places nothing");
    assertEquals(3, store.trainNodes(train.id).size());
  }

  /**
   * A train that had arrived because it had nowhere to go, given somewhere to go. The completion was
   * honest at the time and is wrong now, so it is withdrawn.
   */
  @Test
  void aCompletedTrainThatGainsAConfigPinIsOpenAgain() {
    String image = named("qits-base-image");
    scanned(image, RepositoryArchetype.IMAGE.name());

    MtTrain train = trains.spawn(image, "1.0.0", null, NOON).train();
    assertEquals(TrainStatus.COMPLETED.name(), train.status);

    trains.recordConfigImagePins(train.id, List.of("qits-ci"));

    MtTrain reread = store.train(train.id).orElseThrow();
    assertEquals(TrainStatus.OPEN.name(), reread.status);
    assertNull(reread.completedAt);
  }

  /** Only an IMAGE repository's train has a config side to be owed. */
  @Test
  void aLibrarysTrainIsNeverAConfigPinCandidate() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(consumer, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.MAVEN, coordinate, "0.9.0"));

    MtTrain train = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();

    assertFalse(
        trains.configPinCandidates().stream().anyMatch(candidate -> candidate.id.equals(train.id)));
  }

  // --- the node lifecycle the later evaluation writes through ---------------------------------------

  /**
   * The primitives the adoption evaluation will use, and the reverse read it will find its work
   * with. Nothing here decides anything — the rule that reads a pin or a release and calls these is
   * its own task.
   */
  @Test
  void aNodeMovesPendingToAdoptedToLandedAndTheReverseReadFindsIt() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(consumer, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.MAVEN, coordinate, "0.9.0"));

    MtTrain train = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();
    MtTrainNode node = store.trainNodes(train.id).get(0);
    assertEquals(1, store.nodesOwedBy(consumer).size());

    store.nodeAdopted(node.id, "1.0.0", null, AFTERNOON);
    MtTrainNode adopted = store.trainNode(node.id).orElseThrow();
    assertEquals(TrainNodeState.ADOPTED.name(), adopted.state);
    assertEquals("1.0.0", adopted.adoptedVersion);
    assertEquals(AFTERNOON, adopted.adoptedAt);
    assertEquals(1, store.nodesOwedBy(consumer).size(), "adopted is not landed");

    UUID child = UUID.randomUUID();
    store.nodeLanded(node.id, "1.0.0", child, EVENING);
    MtTrainNode landed = store.trainNode(node.id).orElseThrow();
    assertEquals(TrainNodeState.LANDED.name(), landed.state);
    assertEquals(EVENING, landed.landedAt);
    assertEquals(child, landed.childTrainId, "the adoption's own release is what makes it a graph");
    assertTrue(store.nodesOwedBy(consumer).isEmpty());

    store.completeTrain(train.id, EVENING);
    MtTrain arrived = store.train(train.id).orElseThrow();
    assertEquals(TrainStatus.COMPLETED.name(), arrived.status);
    assertNotNull(arrived.completedAt);
  }

  /**
   * A node first SEEN carrying the version is landed without ever having been seen adopted — the
   * ordinary case, because nothing polls a working tree. The adoption stamp is filled in with the
   * landing, because it certainly happened.
   */
  @Test
  void aNodeLandedWithoutHavingBeenSeenAdoptedStillCarriesAnAdoptionMoment() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    scanned(library, RepositoryArchetype.LIBRARY.name());
    scanned(consumer, RepositoryArchetype.SERVICE.name(), pin(Ecosystem.MAVEN, coordinate, "0.9.0"));

    MtTrain train = trains.spawn(library, "1.0.0", maven(coordinate), NOON).train();
    MtTrainNode node = store.trainNodes(train.id).get(0);

    store.nodeLanded(node.id, "1.1.0", null, EVENING);

    MtTrainNode landed = store.trainNode(node.id).orElseThrow();
    assertEquals(EVENING, landed.adoptedAt);
    assertEquals(
        "1.1.0", landed.adoptedVersion, "a consumer may skip a release and adopt the one after it");
  }
}
