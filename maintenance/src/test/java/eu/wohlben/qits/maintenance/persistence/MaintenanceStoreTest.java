package eu.wohlben.qits.maintenance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
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
    return new ParsedPin(Ecosystem.MAVEN, "pom.xml", name, version, null, location);
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
        List.of(new GroupConfig.Group("dependencies", List.of("*"))),
        GroupSource.DEFAULT,
        (ecosystem, name) -> PinKind.INTERNAL,
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
        List.of(new GroupConfig.Group("dependencies", List.of("*"))),
        GroupSource.DEFAULT,
        (ecosystem, name) -> PinKind.INTERNAL,
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
        List.of(new GroupConfig.Group("dependencies", List.of("*"))),
        GroupSource.DEFAULT,
        (ecosystem, name) -> PinKind.INTERNAL,
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
            new GroupConfig.Group("angular", List.of("@angular/*")),
            new GroupConfig.Group("quarkus", List.of("io.quarkus:*")),
            new GroupConfig.Group("dependencies", List.of("*"))),
        GroupSource.CONFIG,
        (ecosystem, name) -> PinKind.EXTERNAL,
        Instant.now());
    List<MtGroup> groups = store.groups(repository);
    assertEquals(List.of("angular", "quarkus", "dependencies"), groups.stream().map(g -> g.name).toList());
    assertEquals(List.of(0, 1, 2), groups.stream().map(g -> g.ordinal).toList());
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
