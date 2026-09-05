package eu.wohlben.qits.maintenance.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import eu.wohlben.qits.maintenance.model.TrainNodeState;
import eu.wohlben.qits.maintenance.model.TrainStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * <b>The evaluation: what moves a node, and what happens to everybody upstream when a train
 * arrives.</b> Against a real PostgreSQL, because every rule here is a rule about rows.
 *
 * <p>{@code TrainSpawnTest} covers who is put ON a train. This covers the rest of the journey — the
 * SBOM read as evidence of an adoption, the two orderings the estate's two independent consumers can
 * deliver that evidence in, the landing that cascades up a chain, and the two things a dependency
 * cycle must NOT do.
 *
 * <p><b>Every fixture name carries a uuid</b>, the same rule the spawn test follows: this module has
 * no reset helper and the suite shares one database across the class, so isolation is by naming. It
 * also means the estate-wide reads ({@link TrainEvaluator#reevaluate} walks every owed node in the
 * schema) are asserted on this test's own rows rather than on a total.
 *
 * <p><b>The moments are literals.</b> An adoption is stamped with the artifact's {@code occurred_at}
 * and a landing with the arriving train's own moment, so a test that let the clock supply either
 * would be asserting on nothing.
 */
@QuarkusTest
class TrainEvaluationTest {

  private static final Instant MORNING = Instant.parse("2026-09-05T09:00:00Z");
  private static final Instant NOON = Instant.parse("2026-09-05T12:00:00Z");
  private static final Instant AFTERNOON = Instant.parse("2026-09-05T15:00:00Z");
  private static final Instant EVENING = Instant.parse("2026-09-05T19:00:00Z");

  @Inject TrainService trains;

  @Inject TrainEvaluator evaluator;

  @Inject MaintenanceStore store;

  private static String named(String what) {
    return what + "-" + UUID.randomUUID();
  }

  /** A repository as a scan left it: an archetype, and whatever it pins. */
  private void scanned(String repository, ParsedPin... pins) {
    store.replaceInventory(
        repository,
        "qits",
        null,
        RepositoryArchetype.SERVICE.name(),
        "main",
        RepositoryStatus.OK,
        "sha",
        null,
        List.of(pins),
        List.of(),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        MORNING);
  }

  private static ParsedPin pin(String name) {
    return ParsedPin.of(Ecosystem.MAVEN, "pom.xml", name, "0.0.1", null, "dependency:" + name);
  }

  /**
   * A release of {@code repository}: the {@code mt_artifact} row the sibling consumer writes, with
   * its bill of materials already read.
   *
   * <p>The row matters twice over. It is what a train's own coordinates are derived from — {@code
   * mt_train} stores a repository and a version, never a coordinate — and its components are the
   * evidence every adoption below is read out of.
   */
  private UUID publishes(
      String repository, String coordinate, String version, Instant when,
      ParsedSbom.Component... components) {
    UUID id = store.upsertArtifact(Ecosystem.MAVEN, coordinate, version, repository, when);
    store.replaceGraph(id, List.of(components), List.of(), when);
    return id;
  }

  /** One line of a bill of materials. */
  private static ParsedSbom.Component carries(Ecosystem ecosystem, String name, String version) {
    return new ParsedSbom.Component("ref-" + name, "pkg:test/" + name, ecosystem, name, version, true);
  }

  private MtTrainNode node(MtTrain train, String consumer) {
    return store.trainNodes(train.id).stream()
        .filter(node -> consumer.equals(node.consumer))
        .findFirst()
        .orElseThrow(() -> new AssertionError(consumer + " is not on train " + train.id));
  }

  private MtTrain reread(MtTrain train) {
    return store.train(train.id).orElseThrow();
  }

  private static ParsedSbom.Component[] nothing() {
    return new ParsedSbom.Component[0];
  }

  // --- adoption -----------------------------------------------------------------------------

  /**
   * <b>The base case: a consumer's own release names the coordinate at the version the train is
   * carrying, so that consumer has taken it.</b>
   *
   * <p><b>And what is WRITTEN DOWN is the consumer's own released version, not the component version
   * that proved it.</b> The obvious reading is the wrong one, so this is the assertion that pins it:
   * the column is read as half an address — {@code
   * release-requests/by-release/<consumer catalog id>/<adopted_version>} — and that resolver matches
   * the CONSUMER's own release requests. {@code 1.2.0} there would resolve to nothing.
   *
   * <p>The stamp is the ARTIFACT's moment, not the clock. A catch-up that reads four months of
   * documents in a minute would otherwise date every adoption in the estate as having happened
   * today, which is exactly the history a train exists to keep.
   */
  @Test
  void aConsumerWhoseReleaseNamesTheExactVersionHasAdoptedIt() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    String consumerCoordinate = named("eu.wohlben.qits:consumer");

    scanned(library);
    scanned(consumer, pin(coordinate));
    publishes(library, coordinate, "1.2.0", NOON, nothing());

    MtTrain train =
        trains.spawn(library, "1.2.0", new TrainService.ReleasedPackage(Ecosystem.MAVEN, coordinate), NOON)
            .train();
    assertEquals(TrainNodeState.PENDING.name(), node(train, consumer).state);

    UUID released =
        publishes(
            consumer,
            consumerCoordinate,
            "4.0.0",
            AFTERNOON,
            carries(Ecosystem.MAVEN, coordinate, "1.2.0"));
    evaluator.ingested(released);

    MtTrainNode adopted = node(train, consumer);
    assertEquals(TrainNodeState.ADOPTED.name(), adopted.state);
    assertEquals(
        "4.0.0",
        adopted.adoptedVersion,
        "the CONSUMER's own release that carries it, never the 1.2.0 component that proved it");
    assertEquals(AFTERNOON, adopted.adoptedAt, "the artifact's moment, never the clock");
    assertEquals(
        TrainStatus.OPEN.name(),
        reread(train).status,
        "adopted is not landed: nothing has been released with it yet");
  }

  /**
   * <b>The comparison is INCLUSIVE.</b> A consumer that skipped this release and went straight to
   * the one after it is carrying this one's contents; the node that closes is still this train's.
   *
   * <p>And the version recorded is the consumer's release either way — {@code 1.3.0} is the evidence
   * and is not what anybody addresses the adopting release by.
   */
  @Test
  void aConsumerWhoSkippedAheadHasStillAdoptedThisTrainsRelease() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");

    scanned(library);
    scanned(consumer, pin(coordinate));
    publishes(library, coordinate, "1.2.0", NOON, nothing());
    MtTrain train = trains.spawn(library, "1.2.0", null, NOON).train();

    evaluator.ingested(
        publishes(
            consumer,
            named("eu.wohlben.qits:consumer"),
            "4.0.0",
            AFTERNOON,
            carries(Ecosystem.MAVEN, coordinate, "1.3.0")));

    MtTrainNode adopted = node(train, consumer);
    assertEquals(TrainNodeState.ADOPTED.name(), adopted.state);
    assertEquals(
        "4.0.0",
        adopted.adoptedVersion,
        "the consumer's own release, whichever version of the dependency was the evidence");
  }

  /**
   * <b>And a consumer still shipping the OLD copy has adopted nothing</b>, which is the whole state a
   * train exists to make visible. A rule that matched on the name alone would report the estate as
   * having adopted every release the moment it was published.
   */
  @Test
  void aConsumerStillShippingAnOlderVersionHasNotAdoptedAnything() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");

    scanned(library);
    scanned(consumer, pin(coordinate));
    publishes(library, coordinate, "1.2.0", NOON, nothing());
    MtTrain train = trains.spawn(library, "1.2.0", null, NOON).train();

    evaluator.ingested(
        publishes(
            consumer,
            named("eu.wohlben.qits:consumer"),
            "4.0.0",
            AFTERNOON,
            carries(Ecosystem.MAVEN, coordinate, "1.1.0")));

    MtTrainNode owed = node(train, consumer);
    assertEquals(TrainNodeState.PENDING.name(), owed.state);
    assertNull(owed.adoptedAt);
    assertEquals(TrainStatus.OPEN.name(), reread(train).status);
  }

  /**
   * <b>A component whose purl type this service does not map NEVER matches</b> — the same rule the
   * rest of the graph follows ({@code MtArtifactComponent}). A {@code pkg:golang/…} entry whose name
   * happens to collide with a maven coordinate is a name in a world nothing here inventories, and
   * comparing its version with anything would be a coincidence read as a fact.
   */
  @Test
  void aComponentWithNoEcosystemNeverMatchesHoweverWellItsNameLinesUp() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");

    scanned(library);
    scanned(consumer, pin(coordinate));
    publishes(library, coordinate, "1.2.0", NOON, nothing());
    MtTrain train = trains.spawn(library, "1.2.0", null, NOON).train();

    evaluator.ingested(
        publishes(
            consumer,
            named("eu.wohlben.qits:consumer"),
            "4.0.0",
            AFTERNOON,
            // The same name, a higher version, and no ecosystem at all.
            carries(null, coordinate, "9.9.9")));

    assertEquals(TrainNodeState.PENDING.name(), node(train, consumer).state);
  }

  // --- the race between the two consumers of one release ---------------------------------------

  /**
   * <b>THE SBOM ARRIVES BEFORE THE SIBLING TRAIN SPAWN.</b> The adoption is recorded with no link at
   * all, because the train the link points at does not exist yet — and the SPAWN behind it is what
   * resolves the other side.
   *
   * <p>This is the ordering that has no second chance: nothing re-reads a document, so a design that
   * only resolved the link from the ingest side would strand the node until the next boot.
   */
  @Test
  void anSbomIngestedBeforeItsOwnTrainWasSpawnedIsLinkedByTheSpawn() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    String consumerCoordinate = named("eu.wohlben.qits:consumer");

    scanned(library);
    scanned(consumer, pin(coordinate));
    publishes(library, coordinate, "1.2.0", NOON, nothing());
    MtTrain train = trains.spawn(library, "1.2.0", null, NOON).train();

    // The SBOM first. The consumer's own release of 4.0.0 has no station yet.
    evaluator.ingested(
        publishes(
            consumer,
            consumerCoordinate,
            "4.0.0",
            AFTERNOON,
            carries(Ecosystem.MAVEN, coordinate, "1.2.0")));

    MtTrainNode unlinked = node(train, consumer);
    assertEquals(TrainNodeState.ADOPTED.name(), unlinked.state);
    assertNull(unlinked.childTrainId, "there is no train of the consumer's own release yet");

    // …and now the sibling consumer catches up. Nobody pins the consumer, so its station is a
    // journey of length zero and is COMPLETED at creation — which lands the node above.
    MtTrain child =
        trains
            .spawn(
                consumer,
                "4.0.0",
                new TrainService.ReleasedPackage(Ecosystem.MAVEN, consumerCoordinate),
                EVENING)
            .train();
    assertEquals(TrainStatus.COMPLETED.name(), child.status, "a release nobody adopts is degenerate");

    MtTrainNode landed = node(train, consumer);
    assertEquals(TrainNodeState.LANDED.name(), landed.state);
    assertEquals(child.id, landed.childTrainId);
    assertEquals(EVENING, landed.landedAt, "the child's own arrival is when the parent landed");
    assertEquals(AFTERNOON, landed.adoptedAt, "the adoption keeps the moment it was recorded at");

    MtTrain arrived = reread(train);
    assertEquals(TrainStatus.COMPLETED.name(), arrived.status);
    assertEquals(EVENING, arrived.completedAt, "the last landing's moment");
  }

  /**
   * <b>AND THE OTHER WAY ROUND.</b> The train of the consumer's own release is already there when
   * the document lands, so the ingest resolves the link itself — and because that train had already
   * arrived, the adoption and the landing are one step.
   */
  @Test
  void anSbomIngestedAfterItsOwnTrainWasSpawnedResolvesTheLinkItself() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    String consumerCoordinate = named("eu.wohlben.qits:consumer");

    scanned(library);
    scanned(consumer, pin(coordinate));
    publishes(library, coordinate, "1.2.0", NOON, nothing());
    MtTrain train = trains.spawn(library, "1.2.0", null, NOON).train();

    // The spawn first, with no document behind it yet: there is nothing to adopt on.
    MtTrain child =
        trains
            .spawn(
                consumer,
                "4.0.0",
                new TrainService.ReleasedPackage(Ecosystem.MAVEN, consumerCoordinate),
                AFTERNOON)
            .train();
    assertEquals(TrainStatus.COMPLETED.name(), child.status);
    assertEquals(TrainNodeState.PENDING.name(), node(train, consumer).state);

    evaluator.ingested(
        publishes(
            consumer,
            consumerCoordinate,
            "4.0.0",
            EVENING,
            carries(Ecosystem.MAVEN, coordinate, "1.2.0")));

    MtTrainNode landed = node(train, consumer);
    assertEquals(TrainNodeState.LANDED.name(), landed.state);
    assertEquals(child.id, landed.childTrainId);
    assertEquals(TrainStatus.COMPLETED.name(), reread(train).status);
  }

  // --- the cascade ---------------------------------------------------------------------------

  /**
   * <b>A CHAIN LANDS BOTTOM-UP, IN ONE PASS.</b> A library, the component library that takes it, and
   * the service that takes that: when the service's own release finally arrives, all three trains
   * complete off the back of a single ingest.
   *
   * <p>This is the whole point of {@code child_train_id}. Without the cascade, a train would only
   * ever complete when something touched it directly, and the top of every chain would stay open for
   * ever with every node under it landed.
   */
  @Test
  @Timeout(60)
  void aThreeDeepChainLandsBottomUpOffOneArrival() {
    String library = named("lib");
    String libraryCoordinate = named("eu.wohlben.qits:lib");
    String middle = named("mid");
    String middleCoordinate = named("eu.wohlben.qits:mid");
    String top = named("top");
    String topCoordinate = named("eu.wohlben.qits:top");

    scanned(library);
    scanned(middle, pin(libraryCoordinate));
    scanned(top, pin(middleCoordinate));

    publishes(library, libraryCoordinate, "1.0.0", MORNING, nothing());
    MtTrain libraryTrain = trains.spawn(library, "1.0.0", null, MORNING).train();
    assertEquals(List.of(middle), store.trainNodes(libraryTrain.id).stream().map(n -> n.consumer).toList());

    // The middle takes the library and releases; its own station is opened for it, and the top is
    // expected to adopt THAT.
    evaluator.ingested(
        publishes(
            middle,
            middleCoordinate,
            "2.0.0",
            NOON,
            carries(Ecosystem.MAVEN, libraryCoordinate, "1.0.0")));
    MtTrain middleTrain = trains.spawn(middle, "2.0.0", null, NOON).train();
    assertEquals(TrainStatus.OPEN.name(), middleTrain.status);
    assertEquals(middleTrain.id, node(libraryTrain, middle).childTrainId);
    assertEquals(TrainNodeState.ADOPTED.name(), node(libraryTrain, middle).state);

    // And now the top: it takes the middle and releases, and nobody adopts the top.
    evaluator.ingested(
        publishes(
            top,
            topCoordinate,
            "3.0.0",
            AFTERNOON,
            carries(Ecosystem.MAVEN, middleCoordinate, "2.0.0")));
    MtTrain topTrain = trains.spawn(top, "3.0.0", null, EVENING).train();

    assertEquals(TrainStatus.COMPLETED.name(), topTrain.status, "nobody was expected to adopt the top");
    assertEquals(
        TrainNodeState.LANDED.name(),
        node(middleTrain, top).state,
        "the top's release arrived, so the middle's node landed");
    assertEquals(
        TrainStatus.COMPLETED.name(), reread(middleTrain).status, "…which was its last node");
    assertEquals(
        TrainNodeState.LANDED.name(),
        node(libraryTrain, middle).state,
        "and that completion cascaded one further up");
    assertEquals(TrainStatus.COMPLETED.name(), reread(libraryTrain).status);
    assertEquals(
        EVENING,
        reread(libraryTrain).completedAt,
        "every landing in this chain is the top train's own arrival");
  }

  /**
   * <b>A DEPENDENCY CYCLE DEGRADES TO "NEITHER TRAIN EVER COMPLETES", which is the honest answer.</b>
   *
   * <p>Two repositories each carrying the other's release is a real shape (a library and its test
   * fixtures, a service and its client). The completion rule alone is what makes it terminate
   * SEMANTICALLY — a train in a cycle never has all its nodes landed, so nothing completes and
   * nothing cascades — and the timeout on this method is what makes the claim about hanging a claim
   * rather than a hope.
   */
  @Test
  @Timeout(60)
  void aDependencyCycleLeavesBothTrainsOpenAndDoesNotHang() {
    String left = named("left");
    String leftCoordinate = named("eu.wohlben.qits:left");
    String right = named("right");
    String rightCoordinate = named("eu.wohlben.qits:right");

    scanned(left, pin(rightCoordinate));
    scanned(right, pin(leftCoordinate));

    publishes(left, leftCoordinate, "1.0.0", MORNING, carries(Ecosystem.MAVEN, rightCoordinate, "1.0.0"));
    publishes(right, rightCoordinate, "1.0.0", MORNING, carries(Ecosystem.MAVEN, leftCoordinate, "1.0.0"));

    MtTrain leftTrain = trains.spawn(left, "1.0.0", null, NOON).train();
    MtTrain rightTrain = trains.spawn(right, "1.0.0", null, NOON).train();

    evaluator.reevaluate();

    assertEquals(TrainNodeState.ADOPTED.name(), node(leftTrain, right).state);
    assertEquals(TrainNodeState.ADOPTED.name(), node(rightTrain, left).state);
    assertEquals(rightTrain.id, node(leftTrain, right).childTrainId);
    assertEquals(leftTrain.id, node(rightTrain, left).childTrainId);
    assertEquals(TrainStatus.OPEN.name(), reread(leftTrain).status, "a cycle never arrives");
    assertEquals(TrainStatus.OPEN.name(), reread(rightTrain).status);

    // …and a second pass over the same cycle is still a pass rather than a walk.
    evaluator.reevaluate();
    assertEquals(TrainStatus.OPEN.name(), reread(leftTrain).status);
  }

  /**
   * <b>And the guard behind the rule: a cycle whose rows say a train ALREADY arrived is walked once,
   * not for ever.</b>
   *
   * <p>The state below cannot be reached by any writer here — it is one of the two trains completed
   * out from under an unlanded node, the shape a half-applied repair or a hand-edited row leaves. It
   * is exactly what the visited set in the cascade is for: without it the two trains would land each
   * other's nodes in a loop that never ends. With it the walk closes both and stops.
   */
  @Test
  @Timeout(60)
  void aCascadeThroughACycleTerminatesRatherThanLooping() {
    String left = named("cyc-left");
    String leftCoordinate = named("eu.wohlben.qits:cyc-left");
    String right = named("cyc-right");
    String rightCoordinate = named("eu.wohlben.qits:cyc-right");

    scanned(left, pin(rightCoordinate));
    scanned(right, pin(leftCoordinate));
    publishes(left, leftCoordinate, "1.0.0", MORNING, carries(Ecosystem.MAVEN, rightCoordinate, "1.0.0"));
    publishes(right, rightCoordinate, "1.0.0", MORNING, carries(Ecosystem.MAVEN, leftCoordinate, "1.0.0"));
    MtTrain leftTrain = trains.spawn(left, "1.0.0", null, NOON).train();
    MtTrain rightTrain = trains.spawn(right, "1.0.0", null, NOON).train();
    evaluator.reevaluate();

    // The impossible row: one of the two says it arrived while its own node is still owed.
    store.completeTrain(rightTrain.id, EVENING);

    evaluator.reevaluate();

    assertEquals(TrainNodeState.LANDED.name(), node(leftTrain, right).state);
    assertEquals(TrainStatus.COMPLETED.name(), reread(leftTrain).status);
    assertEquals(
        TrainNodeState.LANDED.name(),
        node(rightTrain, left).state,
        "the walk went round once and closed the other side too");
    assertEquals(
        EVENING,
        reread(rightTrain).completedAt,
        "…and did not re-complete a train that was already complete");
  }

  // --- monotonicity and recovery ---------------------------------------------------------------

  /**
   * <b>Nothing here ever moves backwards.</b> A second evaluation with the same evidence writes
   * nothing; one with OLDER evidence writes nothing either. An adoption is a logged fact and a
   * re-derivation that re-stamped it would be this service editing its own history.
   */
  @Test
  void aSecondEvaluationWithTheSameOrOlderEvidenceIsANoOp() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    String consumerCoordinate = named("eu.wohlben.qits:consumer");

    scanned(library);
    scanned(consumer, pin(coordinate));
    publishes(library, coordinate, "1.0.0", MORNING, nothing());
    MtTrain train = trains.spawn(library, "1.0.0", null, MORNING).train();

    UUID first =
        publishes(
            consumer, consumerCoordinate, "4.0.0", NOON, carries(Ecosystem.MAVEN, coordinate, "1.5.0"));
    evaluator.ingested(first);
    MtTrainNode adopted = node(train, consumer);

    // The same document again — a redelivery, or a re-queued sweep.
    evaluator.ingested(first);
    // …and then an OLDER release of the same consumer, ingested late, carrying an older copy.
    evaluator.ingested(
        publishes(
            consumer,
            named("eu.wohlben.qits:older"),
            "3.0.0",
            AFTERNOON,
            carries(Ecosystem.MAVEN, coordinate, "1.0.0")));

    MtTrainNode unchanged = node(train, consumer);
    assertEquals(adopted.state, unchanged.state);
    assertEquals(
        "4.0.0",
        unchanged.adoptedVersion,
        "still the release that was the first evidence, not the 3.0.0 one ingested behind it");
    assertEquals(NOON, unchanged.adoptedAt);
    assertEquals(adopted.childTrainId, unchanged.childTrainId);
  }

  /**
   * <b>THE BOOT STEP, AND THE STATE IT EXISTS FOR.</b> A process that died between an SBOM graph
   * committing and the hook behind it running leaves rows that say a version was adopted and a node
   * that says it was not. Nothing in-flight was lost — the conclusion simply was not drawn — so the
   * repair is to draw it again.
   *
   * <p>And then to draw it a second time and change nothing, which is the property that makes it
   * safe to run on every boot.
   */
  @Test
  void theBootTimeReEvaluationHealsAMissedHookAndIsIdempotent() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");

    scanned(library);
    scanned(consumer, pin(coordinate));
    publishes(library, coordinate, "1.0.0", MORNING, nothing());
    MtTrain train = trains.spawn(library, "1.0.0", null, MORNING).train();

    // The graph, written with NOTHING evaluating behind it — which is the crash this heals.
    publishes(
        consumer,
        named("eu.wohlben.qits:consumer"),
        "4.0.0",
        NOON,
        carries(Ecosystem.MAVEN, coordinate, "1.0.0"));
    assertEquals(TrainNodeState.PENDING.name(), node(train, consumer).state);

    evaluator.reevaluate();

    MtTrainNode healed = node(train, consumer);
    assertEquals(TrainNodeState.ADOPTED.name(), healed.state);
    assertEquals("4.0.0", healed.adoptedVersion, "the consumer's own release, off the artifact row");
    assertEquals(NOON, healed.adoptedAt);

    evaluator.reevaluate();

    MtTrainNode again = node(train, consumer);
    assertEquals(healed.state, again.state);
    assertEquals(healed.adoptedVersion, again.adoptedVersion);
    assertEquals(healed.adoptedAt, again.adoptedAt);
    assertEquals(healed.childTrainId, again.childTrainId);
  }

  /**
   * The other half of the same repair: a train whose last node landed in a process that died before
   * the completion was written. No evaluation would ever revisit it — it has no owed node left — so
   * the belt at the end of the re-evaluation is the only thing that closes it.
   */
  @Test
  void theBootTimeReEvaluationClosesATrainWhoseNodesAllLandedWithNothingWatching() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");

    scanned(library);
    scanned(consumer, pin(coordinate));
    publishes(library, coordinate, "1.0.0", MORNING, nothing());
    MtTrain train = trains.spawn(library, "1.0.0", null, MORNING).train();

    store.nodeLanded(node(train, consumer).id, "1.0.0", null, AFTERNOON);
    assertEquals(TrainStatus.OPEN.name(), reread(train).status);

    evaluator.reevaluate();

    MtTrain arrived = reread(train);
    assertEquals(TrainStatus.COMPLETED.name(), arrived.status);
    assertEquals(AFTERNOON, arrived.completedAt, "the last landing's moment, not the clock");
  }

  /**
   * <b>A SUPERSEDED train keeps evaluating and can still COMPLETE.</b> A repository that took the
   * version this train carried has adopted it, whatever has been released since — supersession
   * decides what a person is shown while a journey is unfinished, not whether the evidence is read.
   */
  @Test
  void aSupersededTrainStillAdoptsAndStillArrives() {
    String library = named("lib");
    String coordinate = named("eu.wohlben.qits:lib");
    String consumer = named("consumer");
    String consumerCoordinate = named("eu.wohlben.qits:consumer");

    scanned(library);
    scanned(consumer, pin(coordinate));
    publishes(library, coordinate, "1.0.0", MORNING, nothing());
    MtTrain first = trains.spawn(library, "1.0.0", null, MORNING).train();
    publishes(library, coordinate, "1.1.0", NOON, nothing());
    trains.spawn(library, "1.1.0", null, NOON);

    assertEquals(TrainStatus.SUPERSEDED.name(), reread(first).status);

    evaluator.ingested(
        publishes(
            consumer,
            consumerCoordinate,
            "4.0.0",
            AFTERNOON,
            carries(Ecosystem.MAVEN, coordinate, "1.0.0")));
    assertEquals(TrainNodeState.ADOPTED.name(), node(first, consumer).state);

    MtTrain child =
        trains
            .spawn(
                consumer,
                "4.0.0",
                new TrainService.ReleasedPackage(Ecosystem.MAVEN, consumerCoordinate),
                EVENING)
            .train();
    assertEquals(TrainStatus.COMPLETED.name(), child.status);

    MtTrain arrived = reread(first);
    assertEquals(
        TrainStatus.COMPLETED.name(),
        arrived.status,
        "a superseded journey that finishes has still finished");
    assertNotNull(arrived.completedAt);
    assertTrue(
        store.trainNodes(first.id).stream()
            .allMatch(node -> TrainNodeState.LANDED.name().equals(node.state)));
  }
}
