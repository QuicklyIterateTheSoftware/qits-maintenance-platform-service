package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.peer.FakePeers;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The REST boundary and the two flows behind it: a scan that fills the inventory, and a bump that
 * asks qits-ci to act on it.
 *
 * <p>The addresses are the shipped ones — the suite inherits {@code
 * quarkus.rest.path=/maintenance/api} from main's application.properties rather than re-declaring
 * it — so a change to the segment fails here rather than in a deployment.
 *
 * <p><b>No test sends an identity header</b>, and that is not a hole: qits-auth-core ships a {@code
 * %test} dev user carrying {@code qits:admin} and {@code qits:system}, so the shipped {@code
 * @RolesAllowed} pair is exercised rather than bypassed. That a real request must carry the pair is
 * pinned in {@code PackagedSurfaceIT}, where the identity contract is real.
 *
 * <p><b>Every poll is a fresh HTTP request and that is load-bearing.</b> A {@code @QuarkusTest}
 * holds ONE request context for the whole method, so a read made in the test thread would be
 * answered from the first session's cache and the row would look unchanged for ever while the
 * worker closed it in another session.
 */
@QuarkusTest
class MaintenanceApiTest {

  private static final String BASE = "/maintenance/api";

  @Inject FakePeers peers;

  @Inject BumpService bumps;

  @Inject InventoryReset inventory;

  @Inject WorkQueue queue;

  @BeforeEach
  void scriptThePeers() {
    // The class shares one database, and an active bump row holds its branch's lock — the next
    // test would be answered 409 by the last one's leftovers. Drain the worker first, or the row
    // being deleted is one a task still holds and the delete lands between its read and its write.
    queue.awaitIdle(Duration.ofSeconds(30));
    inventory.clear();
    peers.reset();
    Fixture.scriptScan(peers);
    Fixture.scriptBranchAbsent(peers);
  }

  /** Queues a scan and waits for its row to close. */
  private String scan() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{\"scope\":\"ALL\"}")
            .when()
            .post(BASE + "/scans")
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    assertEquals("SUCCEEDED", awaitTerminal("/scans/" + id, "SUCCEEDED", "FAILED"));
    return id;
  }

  /** Polls one row the way the client does, until its status is one of the terminal ones. */
  private String awaitTerminal(String path, String... terminal) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      String status =
          given().when().get(BASE + path).then().statusCode(200).extract().path("status");
      for (String candidate : terminal) {
        if (candidate.equals(status)) {
          return status;
        }
      }
      sleep();
    }
    throw new AssertionError(path + " never reached a terminal status");
  }

  /** Polls until the field is set, which is how a test waits for a dispatch without a status. */
  private void awaitField(String path, String field) {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
    while (Instant.now().isBefore(deadline)) {
      Object value = given().when().get(BASE + path).then().statusCode(200).extract().path(field);
      if (value != null) {
        return;
      }
      sleep();
    }
    throw new AssertionError(path + " never set " + field);
  }

  private static void sleep() {
    try {
      Thread.sleep(20);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  // --- the scan -------------------------------------------------------------------------------

  @Test
  void aScanIsAcceptedWithAnIdAndItsRowSaysWhatItCovered() {
    String id = scan();
    given()
        .when()
        .get(BASE + "/scans/" + id)
        .then()
        .statusCode(200)
        .body("scope", equalTo("ALL"))
        .body("repository", nullValue())
        .body("trigger", equalTo("MANUAL"))
        .body("status", equalTo("SUCCEEDED"))
        .body("startedAt", notNullValue())
        .body("finishedAt", notNullValue())
        .body("message", containsString("1 repositories"));
  }

  @Test
  void anUnknownScopeIsRefusedRatherThanTreatedAsEverything() {
    given()
        .contentType(ContentType.JSON)
        .body("{\"scope\":\"SOMETIMES\"}")
        .when()
        .post(BASE + "/scans")
        .then()
        .statusCode(400)
        .body("message", containsString("INTERNAL, EXTERNAL or ALL"));
  }

  @Test
  void aScanFillsTheInventoryAndTheGroupingIsTheRepositorysOwn() {
    scan();
    given()
        .when()
        .get(BASE + "/repositories")
        .then()
        .statusCode(200)
        .body("name", hasItem(Fixture.REPOSITORY))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.status", equalTo("OK"))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.headSha", equalTo(Fixture.HEAD_SHA))
        // The repository declares `angular`; the catch-all is appended so no pin is unclaimed.
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups.name",
            equalTo(java.util.List.of("angular", "dependencies")))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups[0].branch",
            equalTo("maintenance/angular"))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups[0].state", equalTo("NONE"));
  }

  @Test
  void aCatalogRowWithNoNameHasNoAddressAndIsSkipped() {
    scan();
    given().when().get(BASE + "/repositories").then().statusCode(200).body("name", not(hasItem(nullValue())));
  }

  @Test
  void everyEcosystemIsParsedAndTheLocationIsWhereTheVersionIsSet() {
    scan();
    String repository = BASE + "/repositories/" + Fixture.REPOSITORY;
    given()
        .when()
        .get(repository)
        .then()
        .statusCode(200)
        .body("find { it.name == null }", nullValue())
        // maven, through a property
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.version", equalTo("2026.811.1"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.location",
            equalTo("property:qits.eventstream.version"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.kind", equalTo("INTERNAL"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.latest", equalTo("2026.821.3"))
        .body("pins.find { it.name == 'eu.wohlben.qits:qits-eventstream' }.pending", equalTo(true))
        // npm, resolved through the lock, with the range beside it
        .body("pins.find { it.name == '@angular/core' }.version", equalTo("21.0.4"))
        .body("pins.find { it.name == '@angular/core' }.range", equalTo("^21.0.0"))
        .body("pins.find { it.name == '@angular/core' }.kind", equalTo("EXTERNAL"))
        .body("pins.find { it.name == '@angular/core' }.group", equalTo("angular"))
        // docker, by line number
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.version", equalTo("2026.813.1"))
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.location", equalTo("line:2"))
        .body("pins.find { it.name == 'qits/build-images/maven-base' }.latest", equalTo("2026.821.2"));
  }

  @Test
  void anExternalBaseImageIsRecordedAndNeverLookedUp() {
    scan();
    given()
        .when()
        .get(BASE + "/repositories/" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        .body(
            "pins.find { it.name == 'mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image' }.kind",
            equalTo("EXTERNAL"))
        .body(
            "pins.find { it.name == 'mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image' }.latest",
            nullValue());
  }

  @Test
  void aPrereleaseIsNotOfferedToAReleasedPin() {
    // The maven metadata carries 3.35.0.CR1 and the mirror's highest RELEASE is 3.34.6, which is
    // what the pin is offered.
    scan();
    given()
        .when()
        .get(BASE + "/repositories/" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        .body("pins.find { it.name == 'io.quarkus.platform:quarkus-bom' }.latest", equalTo("3.34.6"))
        .body("pins.find { it.name == 'io.quarkus.platform:quarkus-bom' }.pending", equalTo(true));
  }

  @Test
  void whoPinsThisDependencyIsAnswerableAcrossTheWholeInventory() {
    scan();
    given()
        .when()
        .get(BASE + "/dependencies?name=eu.wohlben.qits:*")
        .then()
        .statusCode(200)
        .body("name", hasItem("eu.wohlben.qits:qits-eventstream"))
        .body("find { it.name == 'eu.wohlben.qits:qits-eventstream' }.latest", equalTo("2026.821.3"))
        .body("find { it.name == 'eu.wohlben.qits:qits-eventstream' }.pins[0].repository",
            equalTo(Fixture.REPOSITORY))
        .body("find { it.name == 'eu.wohlben.qits:qits-eventstream' }.pins[0].manifestPath",
            equalTo("pom.xml"))
        .body("find { it.name == 'eu.wohlben.qits:qits-eventstream' }.pins[0].pending", equalTo(true));
  }

  @Test
  void anUnknownRepositoryIsAFourOhFourWithTheMessageEnvelope() {
    given()
        .when()
        .get(BASE + "/repositories/nothing-like-this")
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("message", containsString("nothing-like-this"));
  }

  // --- the bump -------------------------------------------------------------------------------

  /** Requests a bump of the catch-all group and waits until qits-ci has been told. */
  private String requestBump() {
    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    awaitField("/bumps/" + id, "ciRunId");
    return id;
  }

  @Test
  void aBumpSendsExactlyThePendingChangesOfItsGroup() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-1");
    String id = requestBump();

    String payload = peers.bodiesFor("/ci/api/events/trigger").get(0);
    assertTrue(payload.contains("\"name\":\"MaintenanceBump\""), payload);
    assertTrue(payload.contains("\"eventId\":\"" + id + "\""), payload);
    assertTrue(payload.contains("\"repository\":\"" + Fixture.REPOSITORY + "\""), payload);
    assertTrue(payload.contains("\"branch\":\"" + Fixture.BRANCH + "\""), payload);
    assertTrue(payload.contains("\"baseRef\":\"main\""), payload);
    assertTrue(payload.contains("\"location\":\"property:qits.eventstream.version\""), payload);
    assertTrue(payload.contains("\"from\":\"2026.811.1\""), payload);
    assertTrue(payload.contains("\"to\":\"2026.821.3\""), payload);
    // @angular/core belongs to the `angular` group and must not ride along on this branch.
    assertTrue(!payload.contains("@angular/core"), payload);
  }

  @Test
  void aGreenRunThatMovedTheBranchSucceedsAndRecordsTheHead() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-1");
    String id = requestBump();

    // The run ends and the branch moved — the step wrote something.
    Fixture.scriptRun(peers, "run-1", "SUCCESS");
    Fixture.scriptBranchAt(peers, Fixture.BUMPED_SHA);
    bumps.sweep();

    assertEquals("SUCCEEDED", awaitTerminal("/bumps/" + id, "SUCCEEDED", "FAILED", "NOTHING_TO_DO"));
    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .statusCode(200)
        .body("group", equalTo("dependencies"))
        .body("branch", equalTo(Fixture.BRANCH))
        .body("environment", equalTo("dev"))
        .body("trigger", equalTo("MANUAL"))
        .body("ciRunId", equalTo("run-1"))
        .body("ciRunStatus", equalTo("SUCCESS"))
        .body("configPath", containsString("ci-platform-event-maintenance-bump.yml"))
        // The `dependencies` group claims everything `angular` did not: the eventstream property,
        // the quarkus BOM, the @qits npm package and the internal build image.
        .body("changes.size()", equalTo(4))
        .body("finishedAt", notNullValue());

    given()
        .when()
        .get(BASE + "/repositories")
        .then()
        .statusCode(200)
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups.find { it.name == 'dependencies' }.state",
            equalTo("PUSHED"))
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups.find { it.name == 'dependencies' }.headSha",
            equalTo(Fixture.BUMPED_SHA));
  }

  @Test
  void aGreenRunThatDidNotMoveTheBranchIsNothingToDoRatherThanSuccess() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-2");
    String id = requestBump();

    Fixture.scriptRun(peers, "run-2", "SUCCESS");
    // The branch is still absent: the step found the versions already there and wrote nothing.
    bumps.sweep();

    assertEquals(
        "NOTHING_TO_DO", awaitTerminal("/bumps/" + id, "SUCCEEDED", "FAILED", "NOTHING_TO_DO"));
    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .body("message", containsString("did not move"));
  }

  @Test
  void aRedRunAgainstAMovedBranchMeansSomebodyRewroteItByHand() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-3");
    String id = requestBump();

    // The ff-only push was rejected — and the branch moved anyway, which is a person's commit.
    Fixture.scriptRun(peers, "run-3", "FAILED");
    Fixture.scriptBranchAt(peers, Fixture.BUMPED_SHA);
    bumps.sweep();

    assertEquals("FAILED", awaitTerminal("/bumps/" + id, "SUCCEEDED", "FAILED", "NOTHING_TO_DO"));
    given().when().get(BASE + "/bumps/" + id).then().body("message", containsString("rewritten by hand"));
    given()
        .when()
        .get(BASE + "/repositories")
        .then()
        .body("find { it.name == '" + Fixture.REPOSITORY + "' }.groups.find { it.name == 'dependencies' }.state",
            equalTo("STALE"));
  }

  @Test
  void aTriggerAnsweredWithNoRunIsAFailureBecauseNothingIsRunning() {
    scan();
    peers.answer(
        eu.wohlben.qits.maintenance.peer.PeerTarget.CI,
        "/ci/api/events/trigger",
        FakePeers.Scripted.ok(
            "{\"eventId\":\"e1\",\"runIds\":[],\"repositoriesRead\":0,\"repositoriesSkipped\":[]}"));

    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
            .then()
            .statusCode(202)
            .extract()
            .path("id");

    assertEquals("FAILED", awaitTerminal("/bumps/" + id, "SUCCEEDED", "FAILED", "NOTHING_TO_DO"));
    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .body("message", containsString("no run recorded for MaintenanceBump"));
  }

  @Test
  void a503LeavesTheBumpRequestedAndTheNextSweepSendsItAgain() {
    scan();
    peers.answer(
        eu.wohlben.qits.maintenance.peer.PeerTarget.CI,
        "/ci/api/events/trigger",
        FakePeers.Scripted.status(503, "{\"runIds\":[]}"));

    String id =
        given()
            .contentType(ContentType.JSON)
            .body("{}")
            .when()
            .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
            .then()
            .statusCode(202)
            .extract()
            .path("id");

    awaitField("/bumps/" + id, "message");
    given()
        .when()
        .get(BASE + "/bumps/" + id)
        .then()
        .body("status", equalTo("REQUESTED"))
        .body("finishedAt", nullValue())
        .body("changes.size()", equalTo(4));

    // qits-ci is back. The sweep sends the SAME payload under the SAME event id.
    Fixture.scriptCiAccepts(peers, "run-4");
    bumps.sweep();
    awaitField("/bumps/" + id, "ciRunId");
    assertEquals(
        2, peers.bodiesFor("/ci/api/events/trigger").size(), "the trigger should have been retried");
    assertTrue(peers.bodiesFor("/ci/api/events/trigger").get(1).contains("\"eventId\":\"" + id + "\""));
  }

  @Test
  void aSecondBumpOfOneBranchIsRefusedWhileTheFirstIsInFlight() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-5");
    CountDownLatch held = peers.hold();
    try {
      given()
          .contentType(ContentType.JSON)
          .body("{}")
          .when()
          .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
          .then()
          .statusCode(202);

      given()
          .contentType(ContentType.JSON)
          .body("{}")
          .when()
          .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/dependencies/bumps")
          .then()
          .statusCode(409)
          .body("message", containsString("already active"));
    } finally {
      peers.release();
      assertEquals(0, held.getCount());
    }
  }

  @Test
  void bumpingAGroupTheRepositoryDoesNotDeclareIsAFourOhFour() {
    scan();
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post(BASE + "/repositories/" + Fixture.REPOSITORY + "/groups/invented/bumps")
        .then()
        .statusCode(404)
        .body("message", containsString("invented"));
  }

  @Test
  void theBumpListingCarriesTheChangesTooBecauseTheyAreSmall() {
    scan();
    Fixture.scriptCiAccepts(peers, "run-6");
    String id = requestBump();
    given()
        .when()
        .get(BASE + "/bumps?limit=5")
        .then()
        .statusCode(200)
        .body("id", hasItem(id))
        .body("find { it.id == '" + id + "' }.changes.size()", equalTo(4));
    given()
        .when()
        .get(BASE + "/bumps?repository=" + Fixture.REPOSITORY)
        .then()
        .statusCode(200)
        .body("id", hasItem(id));
  }

  @Test
  void anIdThatIsNotAUuidIsAnUnknownRowRatherThanAnError() {
    given().when().get(BASE + "/bumps/not-a-uuid").then().statusCode(404).body("message", notNullValue());
    given().when().get(BASE + "/scans/not-a-uuid").then().statusCode(404).body("message", notNullValue());
  }
}
