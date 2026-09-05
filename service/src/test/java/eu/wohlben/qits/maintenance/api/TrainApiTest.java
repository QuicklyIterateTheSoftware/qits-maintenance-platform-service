package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.maintenance.entity.MtTrain;
import eu.wohlben.qits.maintenance.entity.MtTrainNode;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.train.TrainService;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three routes the journey view reads, over a store seeded through the domain.
 *
 * <p><b>The estate is built by hand rather than by a scan, and the trains are spawned through
 * {@code TrainService} rather than written as rows.</b> What these routes serve is a READ; driving a
 * whole scan to produce three repositories would be testing the scan, and INSERTing train rows
 * directly would let the fixture disagree with what a real release actually writes — which is
 * exactly the disagreement a read model can hide.
 *
 * <p><b>The bus is not in it, and cannot be.</b> A frame cannot be driven into this service from a
 * test, so the release that opens a station is spawned by calling what the listener calls. The
 * listener's own half is {@code bus/ReleaseTrainListenerTest}'s subject.
 *
 * <p>No test sends an identity header: qits-auth-core ships a {@code %test} dev user carrying both
 * roles, so the shipped {@code @RolesAllowed} pair is exercised rather than bypassed. That a real
 * request must carry it is pinned in {@code stories/trains/TrainJourneyIT}, where the identity
 * contract is real.
 */
@QuarkusTest
class TrainApiTest {

  private static final String BASE = "/maintenance/api";

  private static final String TRAINS = BASE + "/trains";

  /** The releasing repository, and qits-projects' own spelling of it. */
  private static final String LIBRARY = "qits-eventstream";

  private static final String LIBRARY_CATALOG_ID = "r-eventstream";

  /** What it publishes, in the spelling a consumer's pin carries. */
  private static final String ARTIFACT = "eu.wohlben.qits:qits-eventstream";

  private static final String VERSION = "2026.905.1";

  /** The version before it — the train this one supersedes. */
  private static final String OLDER_VERSION = "2026.904.1";

  /** A consumer the catalog holds, with an id to address its release request by. */
  private static final String CONSUMER = "qits-ci";

  private static final String CONSUMER_CATALOG_ID = "r1";

  /** …and one the catalog no longer lists, which is a node that is never going to move. */
  private static final String RETIRED = "qits-retired";

  /** An APPLICATION rather than a repository: the config-pin end's consumer namespace. */
  private static final String APPLICATION = "qits-workspaces";

  private static final Instant WHEN = Instant.parse("2026-09-05T09:00:00Z");

  private static final Instant EARLIER = Instant.parse("2026-09-04T09:00:00Z");

  @Inject MaintenanceStore store;

  @Inject TrainService trains;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void anEmptyEstate() {
    // The class shares a database with every other @QuarkusTest here; drain first, so a task still
    // running from the previous class does not write into a store this one just emptied.
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
  }

  // --- the fixture ------------------------------------------------------------------------------

  private void scanned(
      String repository,
      String catalogId,
      RepositoryArchetype archetype,
      RepositoryStatus status,
      List<ParsedPin> pins) {
    store.replaceInventory(
        repository,
        "qits",
        catalogId,
        archetype == null ? null : archetype.name(),
        "main",
        status,
        "sha",
        null,
        pins,
        List.of(),
        GroupSource.DEFAULT,
        pin -> PinKind.INTERNAL,
        WHEN);
  }

  /** One maven pin on the library, as a consumer's pom declares it. */
  private static ParsedPin pinOnTheLibrary(String version) {
    return ParsedPin.of(Ecosystem.MAVEN, "pom.xml", ARTIFACT, version, null, "dependency");
  }

  /**
   * The estate: the library, a consumer the catalog holds and one it does not, both pinning the
   * released coordinate — so a spawn derives two expected adopters.
   */
  private void anEstateThatPinsTheLibrary() {
    scanned(LIBRARY, LIBRARY_CATALOG_ID, RepositoryArchetype.LIBRARY, RepositoryStatus.OK, List.of());
    scanned(
        CONSUMER,
        CONSUMER_CATALOG_ID,
        RepositoryArchetype.SERVICE,
        RepositoryStatus.OK,
        List.of(pinOnTheLibrary(OLDER_VERSION)));
    // No catalog id and no archetype, and ABSENT: the catalog stopped listing it after the last
    // scan read its pins. It is still owed the adoption, and it is never going to make it.
    scanned(RETIRED, null, null, RepositoryStatus.ABSENT, List.of(pinOnTheLibrary(OLDER_VERSION)));
  }

  /** The release itself: the artifact row a real release writes, and the station. */
  private MtTrain released(String version, Instant when) {
    store.upsertArtifact(Ecosystem.MAVEN, ARTIFACT, version, LIBRARY, when);
    return trains
        .spawn(LIBRARY, version, new TrainService.ReleasedPackage(Ecosystem.MAVEN, ARTIFACT), when)
        .train();
  }

  private MtTrainNode nodeOf(UUID trainId, String consumer) {
    return store.trainNodes(trainId).stream()
        .filter(node -> consumer.equals(node.consumer))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no node for " + consumer));
  }

  // --- the index --------------------------------------------------------------------------------

  @Test
  void theIndexIsNewestFirstAndSaysHowFarEachTrainGot() {
    anEstateThatPinsTheLibrary();
    MtTrain older = released(OLDER_VERSION, EARLIER);
    MtTrain newest = released(VERSION, WHEN);

    given()
        .when()
        .get(TRAINS)
        .then()
        .statusCode(200)
        .body("size()", equalTo(2))
        .body("version", contains(VERSION, OLDER_VERSION))
        .body("[0].id", equalTo(newest.id.toString()))
        .body("[0].repository", equalTo(LIBRARY))
        .body("[0].status", equalTo("OPEN"))
        // The wire form of an Instant, asserted rather than assumed: the client renders these and a
        // numeric epoch would render as 1970 with nothing failing on this side.
        .body("[0].createdAt", equalTo("2026-09-05T09:00:00Z"))
        .body("[0].completedAt", nullValue())
        .body("[0].nodeCount", equalTo(2))
        .body("[0].landedCount", equalTo(0))
        // The older station is SUPERSEDED by the newer one — a later release of the same repository
        // is what a person should be looking at instead.
        .body("[1].id", equalTo(older.id.toString()))
        .body("[1].status", equalTo("SUPERSEDED"));
  }

  @Test
  void theIndexTakesARepositoryFilterInEitherSpellingAndALimit() {
    anEstateThatPinsTheLibrary();
    released(OLDER_VERSION, EARLIER);
    released(VERSION, WHEN);

    given()
        .queryParam("repository", LIBRARY)
        .when()
        .get(TRAINS)
        .then()
        .statusCode(200)
        .body("size()", equalTo(2));

    // The caller may be holding qits-projects' spelling — the release-request page has nothing else.
    given()
        .queryParam("repository", LIBRARY_CATALOG_ID)
        .when()
        .get(TRAINS)
        .then()
        .statusCode(200)
        .body("size()", equalTo(2))
        .body("repository", contains(LIBRARY, LIBRARY));

    given()
        .queryParam("repository", CONSUMER)
        .when()
        .get(TRAINS)
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));

    given()
        .queryParam("limit", 1)
        .when()
        .get(TRAINS)
        .then()
        .statusCode(200)
        .body("size()", equalTo(1))
        .body("[0].version", equalTo(VERSION));
  }

  @Test
  void anEmptyStoreIsAnEmptyListRatherThanARefusal() {
    given().when().get(TRAINS).then().statusCode(200).body("size()", equalTo(0));
  }

  // --- one train --------------------------------------------------------------------------------

  @Test
  void oneTrainCarriesWhatItReleasedAndEveryAdopterWithItsAddress() {
    anEstateThatPinsTheLibrary();
    MtTrain train = released(VERSION, WHEN);

    given()
        .when()
        .get(TRAINS + "/" + train.id)
        .then()
        .statusCode(200)
        .body("id", equalTo(train.id.toString()))
        .body("repository", equalTo(LIBRARY))
        .body("version", equalTo(VERSION))
        .body("status", equalTo("OPEN"))
        .body("createdAt", equalTo("2026-09-05T09:00:00Z"))
        .body("completedAt", nullValue())
        .body("supersededBy", nullValue())
        // WHAT LABELS THE STATION: the mt_artifact rows of this (repository, version), which is the
        // same derivation the spawn took its adopters from.
        .body("packages.size()", equalTo(1))
        .body("packages[0].ecosystem", equalTo("maven"))
        .body("packages[0].name", equalTo(ARTIFACT))
        .body("nodes.size()", equalTo(2))
        // THE FIELD THE CROSS-LINK IS BUILT FROM. A node stores a NAME, and qits-projects addresses
        // a repository by its row id, so without this join the journey view has no href.
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.consumerCatalogId",
            equalTo(CONSUMER_CATALOG_ID))
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.consumerStatus", equalTo("OK"))
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.archetype", equalTo("SERVICE"))
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.endKind", equalTo("LINKED"))
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.state", equalTo("PENDING"))
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.adoptedVersion", nullValue())
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.adoptedAt", nullValue())
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.childTrainId", nullValue())
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.landedAt", nullValue())
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.id", notNullValue())
        // AND THE ONE WORTH SURFACING: a train waiting on a repository the catalog no longer lists.
        // It has no id to link to either, because it has no catalog row carrying one.
        .body("nodes.find { it.consumer == '" + RETIRED + "' }.consumerStatus", equalTo("ABSENT"))
        .body("nodes.find { it.consumer == '" + RETIRED + "' }.consumerCatalogId", nullValue())
        .body("nodes.find { it.consumer == '" + RETIRED + "' }.archetype", nullValue());
  }

  @Test
  void aLandedNodeCarriesTheAdoptingReleaseAndTheTrainItOpened() {
    anEstateThatPinsTheLibrary();
    MtTrain train = released(VERSION, WHEN);
    MtTrainNode node = nodeOf(train.id, CONSUMER);
    UUID child = UUID.randomUUID();
    store.nodeLanded(node.id, "2026.905.7", child, WHEN.plusSeconds(3600));

    given()
        .when()
        .get(TRAINS + "/" + train.id)
        .then()
        .statusCode(200)
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.state", equalTo("LANDED"))
        // THE CONSUMER'S OWN RELEASE, not the version of the dependency it took: it is half of the
        // release-request address, and the dependency version would resolve to nothing there.
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.adoptedVersion",
            equalTo("2026.905.7"))
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.adoptedAt", notNullValue())
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.landedAt",
            equalTo("2026-09-05T10:00:00Z"))
        // THE LINK THE CLIENT STITCHES ON. The backend serves one train; this is how the view finds
        // the next station.
        .body("nodes.find { it.consumer == '" + CONSUMER + "' }.childTrainId",
            equalTo(child.toString()));

    given()
        .when()
        .get(TRAINS)
        .then()
        .statusCode(200)
        .body("[0].nodeCount", equalTo(2))
        .body("[0].landedCount", equalTo(1));
  }

  @Test
  void aConfigPinNodeNamesAnApplicationAndResolvesToNoRepository() {
    scanned(LIBRARY, LIBRARY_CATALOG_ID, RepositoryArchetype.IMAGE, RepositoryStatus.OK, List.of());
    MtTrain train = released(VERSION, WHEN);
    trains.recordConfigImagePins(train.id, List.of(APPLICATION));

    given()
        .when()
        .get(TRAINS + "/" + train.id)
        .then()
        .statusCode(200)
        .body("nodes.consumer", hasItem(APPLICATION))
        .body("nodes.find { it.consumer == '" + APPLICATION + "' }.endKind",
            equalTo("CONFIG_IMAGE_PIN"))
        // An application is not a repository, so neither live fact resolves — and that is the
        // ordinary shape of this end rather than a gap in the inventory.
        .body("nodes.find { it.consumer == '" + APPLICATION + "' }.consumerCatalogId", nullValue())
        .body("nodes.find { it.consumer == '" + APPLICATION + "' }.consumerStatus", nullValue());
  }

  @Test
  void aTrainNobodyWasExpectedToAdoptIsAJourneyOfLengthZeroAndNotA404() {
    scanned(LIBRARY, LIBRARY_CATALOG_ID, RepositoryArchetype.LIBRARY, RepositoryStatus.OK, List.of());
    MtTrain train = released(VERSION, WHEN);

    given()
        .when()
        .get(TRAINS + "/" + train.id)
        .then()
        .statusCode(200)
        .body("status", equalTo("COMPLETED"))
        .body("completedAt", equalTo("2026-09-05T09:00:00Z"))
        .body("nodes", empty())
        .body("packages.size()", equalTo(1));
  }

  // --- by release -------------------------------------------------------------------------------

  @Test
  void byReleaseFindsTheStationByNameAndByTheCatalogIdTheCallerHolds() {
    anEstateThatPinsTheLibrary();
    MtTrain train = released(VERSION, WHEN);

    given()
        .queryParam("repository", LIBRARY)
        .queryParam("version", VERSION)
        .when()
        .get(TRAINS + "/by-release")
        .then()
        .statusCode(200)
        .body("id", equalTo(train.id.toString()))
        .body("nodes.size()", equalTo(2));

    // THE UUID-VS-NAME HAZARD, on the route most likely to meet it: the release-request page holds
    // qits-projects' id and nothing else, and mt_train.repository is a name.
    given()
        .queryParam("repository", LIBRARY_CATALOG_ID)
        .queryParam("version", VERSION)
        .when()
        .get(TRAINS + "/by-release")
        .then()
        .statusCode(200)
        .body("id", equalTo(train.id.toString()))
        .body("repository", equalTo(LIBRARY));
  }

  @Test
  void byReleaseWithoutBothHalvesOfTheKeyIsRefusedRatherThanGuessed() {
    given()
        .when()
        .get(TRAINS + "/by-release")
        .then()
        .statusCode(400)
        .body("message", containsString("both a repository and a version"));

    given()
        .queryParam("repository", LIBRARY)
        .when()
        .get(TRAINS + "/by-release")
        .then()
        .statusCode(400);

    given()
        .queryParam("version", VERSION)
        .when()
        .get(TRAINS + "/by-release")
        .then()
        .statusCode(400);
  }

  @Test
  void aReleaseWithNoStationIs404ByName() {
    anEstateThatPinsTheLibrary();
    released(VERSION, WHEN);

    given()
        .queryParam("repository", LIBRARY)
        .queryParam("version", "2026.999.1")
        .when()
        .get(TRAINS + "/by-release")
        .then()
        .statusCode(404)
        .body("message", containsString(LIBRARY))
        .body("message", containsString("2026.999.1"));
  }

  // --- the refusals -----------------------------------------------------------------------------

  @Test
  void anUnknownTrainAndAMalformedIdAreTheSameQuestion() {
    String unknown = UUID.randomUUID().toString();
    given()
        .when()
        .get(TRAINS + "/" + unknown)
        .then()
        .statusCode(404)
        .body("message", containsString(unknown));

    given()
        .when()
        .get(TRAINS + "/not-a-uuid")
        .then()
        .statusCode(404)
        .body("message", containsString("not-a-uuid"));
  }

  /**
   * The literal segment wins over the template, which is the whole reason by-release is a query
   * resolver: {@code /trains/by-release} must never be read as a train whose id is "by-release".
   */
  @Test
  void theByReleaseSegmentIsNotSwallowedByTheIdMatcher() {
    given()
        .when()
        .get(TRAINS + "/by-release")
        .then()
        .statusCode(400)
        .body("message", containsString("by-release lookup"));
  }
}
