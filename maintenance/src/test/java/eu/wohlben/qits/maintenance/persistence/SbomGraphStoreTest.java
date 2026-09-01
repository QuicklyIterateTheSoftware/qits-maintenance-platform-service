package eu.wohlben.qits.maintenance.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three SBOM tables, against a real PostgreSQL the suite spawns itself.
 *
 * <p><b>The reverse query is the subject, and its default view is the load-bearing part.</b> "Who
 * ships a copy of this" has to answer with the NEWEST release of each dependent and not with every
 * release ever: a library released fifty times would otherwise answer fifty times over, and
 * forty-nine of those answers are about versions nobody can change any more. {@code all=true} is
 * the archaeology, and this is where both are pinned.
 *
 * <p>Every method uses a dependency name of its own — this module has no {@code InventoryReset},
 * and the whole point of these reads is that they cross artifacts.
 */
@QuarkusTest
class SbomGraphStoreTest {

  @Inject MaintenanceStore store;

  private String dependency;

  @BeforeEach
  void setUp() {
    dependency = "com.fasterxml:jackson-" + UUID.randomUUID();
  }

  /**
   * The session is cleared before every read, for the reason {@code MaintenanceStoreTest} states: a
   * store WRITE runs in its own transaction on its own session, and a read from the
   * request-bound one would answer from the cache the write never touched.
   */
  private void detached() {
    store.getEntityManager().clear();
  }

  /** One released artifact whose document names {@link #dependency} at {@code embedded}. */
  private UUID release(String name, String version, String repository, String embedded, boolean direct) {
    UUID id =
        store.upsertArtifact(
            Ecosystem.MAVEN,
            name,
            version,
            repository,
            // The publisher's moment, a day apart per major so "newest" is a fact rather than a
            // tie — the ordering under test is occurred_at's and not the insertion order's.
            Instant.parse("2026-09-01T10:00:00Z")
                .plusSeconds(86_400L * Integer.parseInt(version.substring(0, 1))));
    store.replaceGraph(
        id,
        List.of(
            new ParsedSbom.Component(
                "c-1", "pkg:maven/x/y@" + embedded, Ecosystem.MAVEN, dependency, embedded, direct)),
        List.of(new ParsedSbom.Edge(-1, 0)),
        Instant.now());
    return id;
  }

  // --- the reverse query -------------------------------------------------------------------------

  @Test
  void theDefaultViewIsTheNewestReleaseOfEachDependentArtifact() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    String service = "eu.wohlben.qits:qits-svc-" + UUID.randomUUID();
    release(library, "1.0.0", "qits-lib", "2.18.0", true);
    release(library, "3.0.0", "qits-lib", "2.18.2", true);
    release(library, "2.0.0", "qits-lib", "2.18.1", true);
    release(service, "9.0.0", "qits-svc", "2.17.0", false);
    detached();

    List<MaintenanceStore.Dependent> newest = store.dependents(Ecosystem.MAVEN, dependency, true);

    assertEquals(2, newest.size(), "one row per dependent artifact NAME");
    // Sorted by the dependent's name, so the assertion does not depend on insertion order.
    List<String> names = newest.stream().map(row -> row.artifact().name).sorted().toList();
    assertEquals(List.of(library, service).stream().sorted().toList(), names);

    MaintenanceStore.Dependent theLibrary =
        newest.stream().filter(row -> row.artifact().name.equals(library)).findFirst().orElseThrow();
    assertEquals(
        "3.0.0",
        theLibrary.artifact().version,
        "the newest release of the library, not the first or the last written");
    assertEquals("2.18.2", theLibrary.component().version);
    assertEquals("qits-lib", theLibrary.artifact().repository);
    assertTrue(theLibrary.component().direct);
  }

  @Test
  void allTrueIsEveryIngestedVersionRatherThanTheNewestOfEach() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    release(library, "1.0.0", "qits-lib", "2.18.0", true);
    release(library, "2.0.0", "qits-lib", "2.18.1", true);
    release(library, "3.0.0", "qits-lib", "2.18.2", true);
    detached();

    assertEquals(3, store.dependents(Ecosystem.MAVEN, dependency, false).size());
    assertEquals(1, store.dependents(Ecosystem.MAVEN, dependency, true).size());
  }

  /** And within one dependent, the versions come back newest first. */
  @Test
  void theFullViewIsOrderedByDependentNameThenNewestFirst() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    release(library, "1.0.0", "qits-lib", "2.18.0", true);
    release(library, "3.0.0", "qits-lib", "2.18.2", true);
    release(library, "2.0.0", "qits-lib", "2.18.1", true);
    detached();

    List<String> versions =
        store.dependents(Ecosystem.MAVEN, dependency, false).stream()
            .map(row -> row.artifact().version)
            .toList();

    assertEquals(List.of("3.0.0", "2.0.0", "1.0.0"), versions);
  }

  @Test
  void aDependencyNothingEmbedsAnswersWithNothing() {
    assertEquals(List.of(), store.dependents(Ecosystem.MAVEN, "nobody:ships-this", true));
  }

  /** The ecosystem is part of the join and is not a label: two worlds may spell one name. */
  @Test
  void theJoinIsOnTheEcosystemAsWellAsTheName() {
    release("eu.wohlben.qits:qits-lib-" + UUID.randomUUID(), "1.0.0", "qits-lib", "2.18.0", true);
    detached();

    assertEquals(1, store.dependents(Ecosystem.MAVEN, dependency, true).size());
    assertEquals(0, store.dependents(Ecosystem.NPM, dependency, true).size());
  }

  // --- the graph itself --------------------------------------------------------------------------

  @Test
  void replacingAGraphDeletesTheOldComponentsAndTheirEdges() {
    UUID id = release("eu.wohlben.qits:qits-lib-" + UUID.randomUUID(), "1.0.0", "qits-lib", "2.18.0", true);
    detached();
    assertEquals(1, store.components(id).size());
    assertEquals(1, store.edges(id).size());

    store.replaceGraph(
        id,
        List.of(
            new ParsedSbom.Component("c-a", "pkg:npm/a@1", Ecosystem.NPM, "a", "1", true),
            new ParsedSbom.Component("c-b", null, null, "an-unmapped-blob", "7", false)),
        List.of(new ParsedSbom.Edge(-1, 0), new ParsedSbom.Edge(0, 1)),
        Instant.now());
    detached();

    assertEquals(2, store.components(id).size());
    assertEquals(2, store.edges(id).size());
    assertEquals(
        0,
        store.dependents(Ecosystem.MAVEN, dependency, false).size(),
        "the replaced components must stop answering the reverse query");
  }

  /** A component whose purl named a world this service does not inventory is STORED, not dropped. */
  @Test
  void anUnmappedComponentIsStoredWithANullEcosystem() {
    UUID id =
        store.upsertArtifact(
            Ecosystem.MAVEN,
            "eu.wohlben.qits:qits-lib-" + UUID.randomUUID(),
            "1.0.0",
            "qits-lib",
            Instant.now());
    store.replaceGraph(
        id,
        List.of(
            new ParsedSbom.Component(
                "c-go", "pkg:golang/github.com/spf13/cobra@1.8.0", null, "cobra", "1.8.0", false)),
        List.of(),
        Instant.now());
    detached();

    var only = store.components(id).get(0);
    assertNull(only.ecosystem, "a null ecosystem is how 'shown but never matched' is spelled");
    assertEquals("cobra", only.name);
    assertEquals("pkg:golang/github.com/spf13/cobra@1.8.0", only.purl);
  }

  /** An edge pointing outside the component list is dropped rather than written as a broken FK. */
  @Test
  void anEdgeNamingAComponentThatIsNotThereIsSkipped() {
    UUID id =
        store.upsertArtifact(
            Ecosystem.MAVEN,
            "eu.wohlben.qits:qits-lib-" + UUID.randomUUID(),
            "1.0.0",
            "qits-lib",
            Instant.now());
    store.replaceGraph(
        id,
        List.of(new ParsedSbom.Component("c-a", "pkg:npm/a@1", Ecosystem.NPM, "a", "1", true)),
        List.of(new ParsedSbom.Edge(-1, 0), new ParsedSbom.Edge(0, 7), new ParsedSbom.Edge(-1, -1)),
        Instant.now());
    detached();

    assertEquals(1, store.edges(id).size());
  }

  // --- the row itself ----------------------------------------------------------------------------

  @Test
  void oneRowPerReleasedVersionAndAnAnnouncementNeverRewritesAnAnsweredOne() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    UUID first =
        store.upsertArtifact(
            Ecosystem.MAVEN, library, "1.0.0", "qits-lib", Instant.parse("2026-09-01T10:00:00Z"));
    store.markArtifactFailed(first, "qits-artifacts was unreachable");

    UUID second =
        store.upsertArtifact(
            Ecosystem.MAVEN, library, "1.0.0", "elsewhere", Instant.parse("2026-09-02T10:00:00Z"));
    detached();

    assertEquals(first, second);
    MtArtifact row = store.artifact(first).orElseThrow();
    assertEquals(SbomStatus.FAILED.name(), row.sbomStatus);
    assertEquals("qits-artifacts was unreachable", row.sbomError);
    assertEquals("qits-lib", row.repository);
    assertEquals(Instant.parse("2026-09-01T10:00:00Z"), row.occurredAt);
  }

  /** And a re-queue is the one thing that puts a terminal row back to work. */
  @Test
  void aRequeueClearsTheErrorAndPutsTheRowBackToPending() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    UUID id = store.upsertArtifact(Ecosystem.MAVEN, library, "1.0.0", null, Instant.now());
    store.markArtifactMissing(id);
    detached();
    assertEquals(SbomStatus.MISSING.name(), store.artifact(id).orElseThrow().sbomStatus);

    UUID again =
        store.requeueArtifact(Ecosystem.MAVEN, library, "1.0.0", "qits-lib", Instant.now());
    detached();

    assertEquals(id, again);
    MtArtifact row = store.artifact(id).orElseThrow();
    assertEquals(SbomStatus.PENDING.name(), row.sbomStatus);
    assertNull(row.sbomError);
    assertEquals("qits-lib", row.repository, "a backfill may name the repository nothing announced");
  }

  /** The manual backfill of a coordinate nobody announced mints the row from nothing. */
  @Test
  void aRequeueOfAnUnknownCoordinateCreatesTheRow() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    Instant now = Instant.parse("2026-09-03T12:00:00Z");

    UUID id = store.requeueArtifact(Ecosystem.NPM, library, "2.0.0", null, now);
    detached();

    MtArtifact row = store.artifact(id).orElseThrow();
    assertEquals("npm", row.ecosystem);
    assertEquals(SbomStatus.PENDING.name(), row.sbomStatus);
    assertEquals(now, row.occurredAt, "nobody announced it, so now is the honest moment");
    assertNull(row.ingestedAt);
    assertNotNull(row.id);
  }

  @Test
  void theNewestPerNameListingKeepsOneRowPerArtifact() {
    String library = "eu.wohlben.qits:qits-lib-" + UUID.randomUUID();
    store.upsertArtifact(
        Ecosystem.MAVEN, library, "1.0.0", "qits-lib", Instant.parse("2026-09-01T10:00:00Z"));
    store.upsertArtifact(
        Ecosystem.MAVEN, library, "2.0.0", "qits-lib", Instant.parse("2026-09-05T10:00:00Z"));
    detached();

    List<MtArtifact> newest =
        store.newestArtifactPerName().stream()
            .filter(row -> row.name.equals(library))
            .toList();

    assertEquals(1, newest.size());
    assertEquals("2.0.0", newest.get(0).version);
  }

  @Test
  void aRepositorysArtifactsAreReadableByTheNameTheReleaseAnnounced() {
    String repository = "qits-lib-" + UUID.randomUUID();
    store.upsertArtifact(
        Ecosystem.MAVEN, "eu.wohlben.qits:a", "1.0.0", repository, Instant.now());
    store.upsertArtifact(Ecosystem.NPM, "@qits/a", "1.0.0", repository, Instant.now());
    store.upsertArtifact(Ecosystem.MAVEN, "eu.wohlben.qits:b", "1.0.0", "somewhere", Instant.now());
    detached();

    assertEquals(2, store.artifactsOfRepository(repository).size());
  }
}
