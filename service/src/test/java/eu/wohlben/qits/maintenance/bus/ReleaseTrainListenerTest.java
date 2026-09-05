package eu.wohlben.qits.maintenance.bus;

import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.frame;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.softwareReleasePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.maintenance.entity.MtTrain;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.TrainStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.train.TrainService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The train listener's decision, in isolation from the bus and the database.
 *
 * <p>What it is about is WHICH FRAMES REACH THE SPAWN AND WITH WHAT — the key they fold into, the
 * moment they carry, and the two failure arms. What the spawn then does with them — the membership,
 * the idempotence, the supersession — is {@code train/TrainSpawnTest}'s, against a real PostgreSQL.
 *
 * <p>The payloads come from {@link ForeignEventContractTest}'s transcription through the real {@code
 * CanonicalJson}, so the bytes here are the bytes qits-ci publishes rather than a JSON literal this
 * file guessed.
 */
class ReleaseTrainListenerTest {

  /** A {@link MaintenanceStore} whose catalog is a map. */
  private static final class RecordingStore extends MaintenanceStore {

    final Map<String, String> nameByCatalogId = new LinkedHashMap<>();

    RuntimeException failWith;

    @Override
    public String repositoryName(String spelling) {
      if (failWith != null) {
        throw failWith;
      }
      if (spelling == null) {
        return null;
      }
      return nameByCatalogId.getOrDefault(spelling, spelling);
    }
  }

  /** The write seam: what the listener asks for, recorded rather than done. */
  private static final class RecordingTrains extends TrainService {

    record Spawn(String repository, String version, ReleasedPackage announced, Instant occurredAt) {}

    final List<Spawn> spawns = new ArrayList<>();

    RuntimeException failWith;

    @Override
    public MaintenanceStore.TrainSpawn spawn(
        String repository, String version, ReleasedPackage announced, Instant occurredAt) {
      if (failWith != null) {
        throw failWith;
      }
      spawns.add(new Spawn(repository, version, announced, occurredAt));
      MtTrain train = new MtTrain();
      train.id = UUID.randomUUID();
      train.repository = repository;
      train.version = version;
      train.status = TrainStatus.OPEN.name();
      train.createdAt = occurredAt;
      return new MaintenanceStore.TrainSpawn(train, true, 0);
    }
  }

  private ReleaseTrainListener listener;
  private RecordingStore store;
  private RecordingTrains trains;

  @BeforeEach
  void setUp() {
    store = new RecordingStore();
    trains = new RecordingTrains();
    listener = new ReleaseTrainListener();
    listener.store = store;
    listener.trains = trains;
  }

  private void release(String packageType, String packageName, String version) {
    listener.onFrame(
        frame("SoftwareRelease", softwareReleasePayload(packageType, packageName, version)));
  }

  @Test
  void itSubscribesToTheOneSignatureUnderItsOwnStorageKey() {
    assertEquals(Set.of("SoftwareRelease"), listener.signatures());
    assertEquals("maintenance-release-trains", listener.consumerId());
  }

  /**
   * The three ecosystem types hand the spawn a coordinate to look adopters up by. It is the same
   * {@code packageName} spelling {@code mt_pin} uses, because that is the join.
   */
  @Test
  void anEcosystemReleaseCarriesItsCoordinateIntoTheSpawn() {
    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1");
    release("npm", "@qits/ui-components", "3.2.0");
    release("docker", "qits/qits-ci", "2026.901.2");

    assertEquals(
        List.of(
            new TrainService.ReleasedPackage(Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream"),
            new TrainService.ReleasedPackage(Ecosystem.NPM, "@qits/ui-components"),
            new TrainService.ReleasedPackage(Ecosystem.DOCKER, "qits/qits-ci")),
        trains.spawns.stream().map(RecordingTrains.Spawn::announced).toList());
  }

  /**
   * <b>THE DIVERGENCE FROM {@link SoftwareReleaseListener}, and the whole reason this is a second
   * listener rather than a line in that one.</b> Its sibling settles {@code daemon} and {@code docs}
   * at DEBUG because nothing in any manifest pins either. A train is not an inventory row: a daemon
   * release is a real journey (the service that hands it out has to adopt it), and a docs release is
   * one package of a release that has a station either way.
   */
  @Test
  void aDaemonAndADocsReleaseBothReachTheSpawnWithNoCoordinate() {
    release("daemon", "qits-ci-daemon", "2026.901.1");
    release("docs", "@apidocs/qits-ci", "2026.901.1");
    release("cargo", "qits-something", "1.0.0");

    assertEquals(3, trains.spawns.size(), "every release gets a station");
    for (RecordingTrains.Spawn spawn : trains.spawns) {
      assertNull(spawn.announced(), "there is no coordinate any manifest could pin");
    }
  }

  /**
   * Every packageType of one release aims at the SAME {@code (repository, version)} key. A pipeline
   * publishing four packages emits four frames and they are one release; the store is what makes the
   * second, third and fourth settle rather than mint.
   */
  @Test
  void everyPackageOfOneReleaseFoldsIntoOneStation() {
    release("maven", "eu.wohlben.qits:qits-ci", "2026.905.1");
    release("npm", "@qits/ci-spa", "2026.905.1");
    release("docker", "qits/qits-ci", "2026.905.1");
    release("docs", "@apidocs/qits-ci", "2026.905.1");

    assertEquals(
        1,
        trains.spawns.stream()
            .map(spawn -> spawn.repository() + " " + spawn.version())
            .distinct()
            .count(),
        "the station is the (repository, version), never the package");
  }

  /**
   * <b>The wire field is qits-projects' ROW ID</b>, measured live 2026-09-02. Here it matters twice
   * over: a uuid would not merely write an unjoinable column, it would make the supersession key
   * wrong, so the name-spelled and uuid-spelled trains of ONE repository would never supersede each
   * other.
   */
  @Test
  void aRepositoryNamedByItsCatalogRowIdIsSpawnedUnderItsName() {
    store.nameByCatalogId.put("daf73ae4-1c9a-4f8e-9a51-0b0d0e0f1234", "qits-eventstream-javalib");

    listener.onFrame(
        frame(
            "SoftwareRelease",
            softwareReleasePayload(
                "daf73ae4-1c9a-4f8e-9a51-0b0d0e0f1234",
                "maven",
                "eu.wohlben.qits:qits-eventstream",
                "2026.901.1")));

    assertEquals("qits-eventstream-javalib", trains.spawns.get(0).repository());
  }

  /** A spelling the catalog knows neither way is KEPT: the release still happened. */
  @Test
  void aSpellingTheCatalogKnowsNeitherWayIsKeptRawRatherThanDropped() {
    listener.onFrame(
        frame(
            "SoftwareRelease",
            softwareReleasePayload(
                "8f7e6d5c-0000-4000-8000-1a2b3c4d5e6f", "maven", "g:a", "2026.901.1")));

    assertEquals("8f7e6d5c-0000-4000-8000-1a2b3c4d5e6f", trains.spawns.get(0).repository());
  }

  /**
   * <b>The frame's moment, never the clock</b>, and here it is load-bearing rather than tidy: it is
   * the key supersession is decided on. A catch-up replaying four releases in as many seconds would
   * otherwise order them by arrival.
   */
  @Test
  void theStationIsStampedWithThePublishersMomentAndNotWithTheClock() {
    EventFrame published =
        frame("SoftwareRelease", softwareReleasePayload("maven", "g:a", "2026.901.1"));

    listener.onFrame(published);

    assertEquals(published.occurredAt(), trains.spawns.get(0).occurredAt());
  }

  @Test
  void anUnreadablePayloadIsPoisonAndIsSettled() {
    listener.onFrame(frame("SoftwareRelease", "not json at all"));

    assertTrue(trains.spawns.isEmpty());
  }

  /**
   * The two essentials. A release with no version has no station to open, and one with no repository
   * has no journey: neither is retryable, so both are a WARN and a settle.
   */
  @Test
  void aReleaseMissingItsRepositoryOrItsVersionIsPoisonAndIsSettled() {
    release("maven", "g:a", "");
    listener.onFrame(frame("SoftwareRelease", softwareReleasePayload("", "maven", "g:a", "1.0.0")));

    assertTrue(trains.spawns.isEmpty());
  }

  /**
   * The other half of the failure rule: a store that could not answer is a condition rather than a
   * verdict, so it is left to throw — the claim rolls back and the release stays owed.
   */
  @Test
  void aStoreThatWillNotAnswerIsLeftToThrowSoTheReleaseStaysOwed() {
    store.failWith = new IllegalStateException("the database is not there");

    assertThrows(IllegalStateException.class, () -> release("maven", "g:a", "2026.901.1"));
  }

  /** And so is a spawn that could not write. Same arm, further in. */
  @Test
  void aSpawnThatWillNotWriteIsLeftToThrowToo() {
    trains.failWith = new IllegalStateException("the database is not there");

    assertThrows(IllegalStateException.class, () -> release("maven", "g:a", "2026.901.1"));
  }
}
