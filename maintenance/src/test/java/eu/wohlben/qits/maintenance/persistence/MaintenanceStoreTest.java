package eu.wohlben.qits.maintenance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.entity.MtScan;
import eu.wohlben.qits.maintenance.error.BumpAlreadyActiveException;
import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.model.ScanStatus;
import eu.wohlben.qits.maintenance.pending.Change;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The store, against a real PostgreSQL the suite spawns itself.
 *
 * <p>Every migration runs against an empty schema here, so the schema and the entities are checked
 * against each other rather than against a mapping tool's opinion.
 */
@QuarkusTest
class MaintenanceStoreTest {

  @Inject MaintenanceStore store;

  private static ParsedPin pin(String name, String version, String location) {
    return ParsedPin.of(Ecosystem.MAVEN, "pom.xml", name, version, null, location);
  }

  @Test
  void anInventoryIsReplacedWholesaleRatherThanMerged() {
    String repository = "replace-" + UUID.randomUUID();
    store.replaceInventory(
        repository,
        "qits",
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(pin("g:a", "1.0.0", "dependency:g:a"), pin("g:b", "2.0.0", "dependency:g:b")),
        List.of(GroupConfig.Group.ofKind("dependencies", PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    assertEquals(2, store.pins(repository).size());

    // The second scan found only one pin. The first scan's other row must be gone, not merged.
    store.replaceInventory(
        repository,
        "qits",
        "main",
        RepositoryStatus.OK,
        "sha2",
        null,
        List.of(pin("g:a", "1.1.0", "dependency:g:a")),
        List.of(GroupConfig.Group.ofKind("dependencies", PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    List<MtPin> pins = store.pins(repository);
    assertEquals(1, pins.size());
    assertEquals("1.1.0", pins.get(0).version);
    assertEquals("sha2", store.repository(repository).orElseThrow().headSha);
  }

  @Test
  void anUnreachableRepositoryKeepsThePinsItHad() {
    // A peer that could not be asked is not evidence that a repository stopped pinning anything.
    String repository = "unreachable-" + UUID.randomUUID();
    store.replaceInventory(
        repository,
        "qits",
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(pin("g:a", "1.0.0", "dependency:g:a")),
        List.of(GroupConfig.Group.ofKind("dependencies", PinKind.INTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());

    store.markRepository(
        repository, "qits", RepositoryStatus.UNREACHABLE, "the git host said nothing", Instant.now());

    MtRepository row = store.repository(repository).orElseThrow();
    assertEquals(RepositoryStatus.UNREACHABLE.name(), row.status);
    assertEquals(1, store.pins(repository).size());
  }

  @Test
  void groupsKeepTheirDeclarationOrder() {
    String repository = "groups-" + UUID.randomUUID();
    store.replaceInventory(
        repository,
        "qits",
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(),
        List.of(
            GroupConfig.Group.glob("angular", List.of("@angular/*")),
            GroupConfig.Group.glob("quarkus", List.of("io.quarkus:*")),
            GroupConfig.Group.ofKind("dependencies", PinKind.INTERNAL)),
        GroupSource.CONFIG,
        candidate -> PinKind.EXTERNAL,
        Instant.now());
    List<MtGroup> groups = store.groups(repository);
    assertEquals(List.of("angular", "quarkus", "dependencies"), groups.stream().map(g -> g.name).toList());
    assertEquals(List.of(0, 1, 2), groups.stream().map(g -> g.ordinal).toList());
  }

  @Test
  void aGroupsKindSurvivesTheRoundTripAndAGlobGroupCarriesNone() {
    // The column is what the split rides on: a scan writes the fallback pair with a kind and empty
    // patterns, a configured group with patterns and no kind, and the grouping is read back out of
    // these rows on every request.
    String repository = "kinds-" + UUID.randomUUID();
    store.replaceInventory(
        repository,
        "qits",
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(pin("g:a", "1.0.0", "dependency:g:a")),
        List.of(
            GroupConfig.Group.glob("angular", List.of("@angular/*")),
            GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL),
            GroupConfig.Group.ofKind(GroupConfig.EXTERNAL_GROUP, PinKind.EXTERNAL)),
        GroupSource.CONFIG,
        candidate -> PinKind.INTERNAL,
        Instant.now());

    List<MtGroup> groups = store.groups(repository);
    assertEquals(List.of("angular", "dependencies", "external"), groups.stream().map(g -> g.name).toList());
    assertNull(groups.get(0).kind);
    assertEquals("[\"@angular/*\"]", groups.get(0).patterns);
    assertEquals(PinKind.INTERNAL.name(), groups.get(1).kind);
    assertEquals(PinKind.EXTERNAL.name(), groups.get(2).kind);
    // A kind group claims by kind and by nothing else, so its patterns are the empty array.
    assertEquals("[]", groups.get(1).patterns);
    assertEquals("[]", groups.get(2).patterns);

    // And the pin lands where its kind says, read the way every route reads it.
    assertEquals(
        "dependencies",
        eu.wohlben.qits.maintenance.pending.PendingChanges.groupOf(store.pins(repository).get(0), groups)
            .orElseThrow());
  }

  @Test
  void aRescanReplacesTheGroupingAsWellAsThePins() {
    // The rows are a cache of a file, and a repository that ADDS a maintenance.yml must not keep
    // the fallback pair beside the groups it just declared.
    String repository = "regroup-" + UUID.randomUUID();
    store.replaceInventory(
        repository,
        "qits",
        "main",
        RepositoryStatus.OK,
        "sha1",
        null,
        List.of(),
        List.of(
            GroupConfig.Group.ofKind(GroupConfig.DEFAULT_GROUP, PinKind.INTERNAL),
            GroupConfig.Group.ofKind(GroupConfig.EXTERNAL_GROUP, PinKind.EXTERNAL)),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    store.replaceInventory(
        repository,
        "qits",
        "main",
        RepositoryStatus.OK,
        "sha2",
        null,
        List.of(),
        List.of(GroupConfig.Group.glob("everything", List.of("*"))),
        GroupSource.CONFIG,
        candidate -> PinKind.INTERNAL,
        Instant.now());
    List<MtGroup> groups = store.groups(repository);
    assertEquals(List.of("everything"), groups.stream().map(g -> g.name).toList());
    assertNull(groups.get(0).kind);
  }

  @Test
  void aFailedLookupIsWrittenBecauseItIsNotTheSameAsUpToDate() {
    String name = "g:" + UUID.randomUUID();
    store.recordLatest(
        Ecosystem.MAVEN,
        name,
        LatestLookup.failed("http://example/maven-metadata.xml", "HTTP 503"),
        Instant.now());
    var row = store.latest(Ecosystem.MAVEN, name).orElseThrow();
    assertNull(row.latest);
    assertEquals("HTTP 503", row.error);
    assertNotNull(row.sourceUrl);
  }

  @Test
  void aLatestRowIsOnePerDependencyAndIsOverwritten() {
    String name = "g:" + UUID.randomUUID();
    store.recordLatest(Ecosystem.MAVEN, name, LatestLookup.found("1.0.0", "u"), Instant.now());
    store.recordLatest(Ecosystem.MAVEN, name, LatestLookup.found("2.0.0", "u"), Instant.now());
    assertEquals("2.0.0", store.latest(Ecosystem.MAVEN, name).orElseThrow().latest);
  }

  /**
   * The bus's write, and the rule that separates it from the poll's: an announcement is evidence
   * that THIS version exists and never that a higher one does not, so a catch-up frame from before
   * the last scan must not rewind a column that scan filled.
   *
   * <p><b>Every write happens before the one read, and that is not style.</b> A {@code @QuarkusTest}
   * method runs inside ONE request context, so a read made outside a transaction is answered by the
   * request-bound session — while every store write runs in {@code DbRetry.inNewTx}, whose session is
   * bound to its own transaction. Read the row in between and the first session's first-level cache
   * answers every later read with the value it saw first, and an assertion about the last write fails
   * against a store that is perfectly correct. Measured here, 2026-09-01. Same rock as the API
   * suite's "every poll is a fresh HTTP request".
   */
  @Test
  void anAnnouncedLatestOnlyEverMovesForward() {
    String name = "g:" + UUID.randomUUID();

    assertTrue(
        store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.901.5", "event:one", Instant.now()),
        "the first announcement of a dependency nothing has looked up is adopted");
    assertFalse(
        store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.825.1", "event:stale", Instant.now()),
        "a caught-up frame from before the last scan must not rewind the column");
    assertFalse(
        store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.901.5", "event:again", Instant.now()),
        "and the ordinary redelivery of one release writes nothing at all");
    assertTrue(
        store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.902.1", "event:next", Instant.now()),
        "the next real release moves it on");

    var row = store.latest(Ecosystem.MAVEN, name).orElseThrow();
    assertEquals("2026.902.1", row.latest);
    assertEquals("event:next", row.sourceUrl);
  }

  /**
   * The other half of the same rule, stated as a row rather than as a return value: a frame that is
   * not newer writes NOTHING — not the version, not the source url, not {@code checked_at}. Stamping
   * the timestamp would say a lookup happened, and none did.
   */
  @Test
  void aFrameThatIsNotNewerLeavesTheRowExactlyAsItWas() {
    String name = "g:" + UUID.randomUUID();
    store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.901.5", "event:one", Instant.now());
    store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.825.1", "event:stale", Instant.now());
    store.recordLatestIfNewer(Ecosystem.MAVEN, name, "2026.901.5", "event:again", Instant.now());

    var row = store.latest(Ecosystem.MAVEN, name).orElseThrow();
    assertEquals("2026.901.5", row.latest);
    assertEquals("event:one", row.sourceUrl, "not even the source url is restamped");
  }

  /**
   * A row whose lookup FAILED holds no version, so the announcement is the first thing known about
   * that dependency and is adopted — and it clears the error with it, because "we could not find
   * out" is no longer true.
   */
  @Test
  void anAnnouncementAdoptsARowWhoseLookupHadFailedAndClearsItsError() {
    String name = "g:" + UUID.randomUUID();
    store.recordLatest(
        Ecosystem.MAVEN, name, LatestLookup.failed("http://example/x", "HTTP 503"), Instant.now());

    assertTrue(
        store.recordLatestIfNewer(Ecosystem.MAVEN, name, "1.2.3", "event:one", Instant.now()));

    var row = store.latest(Ecosystem.MAVEN, name).orElseThrow();
    assertEquals("1.2.3", row.latest);
    assertNull(row.error);
  }

  /** npm ranks prereleases by rules maven does not share, and the guard is the ecosystem's own. */
  @Test
  void theForwardGuardIsTheEcosystemsOwnVersionOrder() {
    String name = "@qits/" + UUID.randomUUID();
    assertTrue(store.recordLatestIfNewer(Ecosystem.NPM, name, "1.0.0", "event:one", Instant.now()));
    assertFalse(
        store.recordLatestIfNewer(Ecosystem.NPM, name, "1.0.0-rc.1", "event:two", Instant.now()),
        "semver puts a prerelease below its own release");
  }

  /**
   * The bus's debounce: a merge is a burst of pushes, and a scan of one repository already queued or
   * running covers every one of them.
   */
  @Test
  void aScanOfOneRepositoryIsPendingWhileItIsQueuedOrRunningAndNotAfter() {
    String repository = "debounce-" + UUID.randomUUID();
    assertFalse(store.scanPending(repository));

    UUID id = store.openScan(ScanScope.INTERNAL, repository, "EVENT", Instant.now());
    assertTrue(store.scanPending(repository), "REQUESTED is queued behind the worker");

    store.scanStatus(id, ScanStatus.RUNNING, null, Instant.now());
    assertTrue(store.scanPending(repository));

    store.scanStatus(id, ScanStatus.SUCCEEDED, "done", Instant.now());
    assertFalse(store.scanPending(repository), "a finished scan debounces nothing");

    // A whole-catalog scan is deliberately not counted: it covers this repository, but it runs for
    // minutes, and suppressing an event's rescan for its length would read a push at whatever
    // revision that scan happened to reach.
    store.openScan(ScanScope.ALL, null, "SCHEDULED", Instant.now());
    assertFalse(store.scanPending(repository));
  }

  @Test
  void aRestartClosesEveryScanADeadProcessLeftOpen() {
    // A scan's work is entirely in the process it ran in: reads it made, rows it wrote, a position
    // in a loop nothing recorded. A successor cannot resume one, so a row left RUNNING for ever is
    // indistinguishable from a slow scan — which is what the first live failure looked like.
    UUID running = store.openScan(ScanScope.ALL, null, "MANUAL", Instant.now());
    store.scanStatus(running, ScanStatus.RUNNING, null, Instant.now());
    UUID queued = store.openScan(ScanScope.INTERNAL, "qits-ci", "SCHEDULED", Instant.now());
    UUID done = store.openScan(ScanScope.ALL, null, "MANUAL", Instant.now());
    store.scanStatus(done, ScanStatus.SUCCEEDED, "all good", Instant.now());

    assertTrue(store.failInterruptedScans("interrupted by restart", Instant.now()) >= 2);

    for (UUID id : List.of(running, queued)) {
      MtScan row = store.scan(id).orElseThrow();
      assertEquals(ScanStatus.FAILED.name(), row.status);
      assertEquals("interrupted by restart", row.message);
      assertNotNull(row.finishedAt);
    }
    // A scan that had already ended keeps the ending it earned.
    assertEquals(ScanStatus.SUCCEEDED.name(), store.scan(done).orElseThrow().status);
    assertEquals("all good", store.scan(done).orElseThrow().message);
  }

  @Test
  void aSecondBumpOfOneBranchIsRefusedInsideTheOpeningTransaction() {
    String repository = "bump-" + UUID.randomUUID();
    UUID first =
        store.openBump(
            repository,
            "dependencies",
            "maintenance/dependencies",
            "dev",
            BumpTrigger.MANUAL,
            List.of(new Change("maven", "pom.xml", "g:a", "1.0.0", "1.1.0", "dependency:g:a")),
            Instant.now());
    BumpAlreadyActiveException refused =
        assertThrows(
            BumpAlreadyActiveException.class,
            () ->
                store.openBump(
                    repository,
                    "dependencies",
                    "maintenance/dependencies",
                    "dev",
                    BumpTrigger.SCHEDULED,
                    List.of(),
                    Instant.now()));
    assertEquals(first, refused.activeBumpId());
    assertEquals(409, refused.statusCode());
  }

  @Test
  void aFinishedBumpReleasesTheBranchForTheNextOne() {
    String repository = "bump-again-" + UUID.randomUUID();
    UUID first =
        store.openBump(
            repository, "dependencies", "maintenance/dependencies", "dev", BumpTrigger.MANUAL, List.of(), Instant.now());
    store.bumpFinished(first, BumpStatus.SUCCEEDED, "SUCCESS", "done", Instant.now());
    UUID second =
        store.openBump(
            repository, "dependencies", "maintenance/dependencies", "dev", BumpTrigger.MANUAL, List.of(), Instant.now());
    assertTrue(store.activeBump(repository, "dependencies").isPresent());
    MtBump row = store.bump(first).orElseThrow();
    assertNotNull(row.finishedAt);
    assertEquals("SUCCESS", row.ciRunStatus);
    assertNotNull(second);
  }

  @Test
  void theChangesAreStoredAsSentAndReadBackWhole() {
    String repository = "changes-" + UUID.randomUUID();
    UUID id =
        store.openBump(
            repository,
            "dependencies",
            "maintenance/dependencies",
            "dev",
            BumpTrigger.MANUAL,
            List.of(
                new Change(
                    "docker", "Dockerfile", "qits/build-images/maven-base", "1", "2", "line:3")),
            Instant.now());
    var changes = MaintenanceStore.readObjects(store.bump(id).orElseThrow().changes);
    assertEquals(1, changes.size());
    assertEquals("line:3", changes.get(0).get("location"));
    assertEquals("qits/build-images/maven-base", changes.get(0).get("name"));
  }

  @Test
  void aBumpThatWasDispatchedCarriesItsEventAndItsRuns() {
    String repository = "dispatch-" + UUID.randomUUID();
    UUID id =
        store.openBump(
            repository, "dependencies", "maintenance/dependencies", "dev", BumpTrigger.MANUAL, List.of(), Instant.now());
    store.bumpDispatched(id, id.toString(), List.of("run-1", "run-2"));
    MtBump row = store.bump(id).orElseThrow();
    assertEquals(BumpStatus.RUNNING.name(), row.status);
    assertEquals("run-1,run-2", row.ciRunId);
    assertEquals(id.toString(), row.ciEventId);
  }
}
