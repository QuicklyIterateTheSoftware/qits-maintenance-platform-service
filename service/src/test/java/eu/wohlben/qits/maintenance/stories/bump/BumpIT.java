package eu.wohlben.qits.maintenance.stories.bump;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.bump.CiClient;
import eu.wohlben.qits.maintenance.stories.inventory.InventoryIT;
import eu.wohlben.qits.maintenance.stories.support.StoryCatalog;
import eu.wohlben.qits.maintenance.stories.support.StoryIdentities;
import eu.wohlben.qits.maintenance.stories.support.StoryNetwork;
import eu.wohlben.qits.maintenance.stories.support.StoryPeers;
import eu.wohlben.qits.maintenance.stories.support.StoryProfile;
import eu.wohlben.qits.maintenance.stories.support.StoryTarget;
import eu.wohlben.qits.maintenance.stories.support.StoryWaits;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.Network;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.UserflowRunsAfter;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.Slugs;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * <b>The two things this service makes happen anywhere else</b> — and the shape of them is the whole
 * design: <i>this service DECIDES; a CI step APPLIES; a door RELEASES.</i>
 *
 * <p>Nothing here clones a repository, edits a file or pushes a ref. A bump is a payload naming a
 * file, a location and two versions, handed to qits-ci as a {@code MaintenanceBump} trigger, and
 * the step that reads it is the only thing that touches anybody's tree. When the branch has moved,
 * this service asks qits-workspaces' release door for a release REQUEST on it — nothing merges at
 * that call either; the quality gates settle the request afterwards. That is why the interesting
 * evidence is <b>what the two peers were handed</b>, read back off the wire rather than out of this
 * service's own row.
 *
 * <p><b>The two stories are the two endings a green run can have</b>, and telling them apart is a
 * rule nobody would guess:
 *
 * <ul>
 *   <li><b>the branch moved</b>: SUCCEEDED. The step found the versions and wrote them. Along the
 *       way a second request for the same group is refused 409 — two runs writing one branch would
 *       make the second a non-ff rejection at best.
 *   <li><b>the branch did not</b>: NOTHING_TO_DO, which is a real outcome and reads very differently
 *       from SUCCEEDED in a list of nightly bumps. Only the HEAD is compared, never a commit count:
 *       one bump is up to two commits, because the maven step and the node/docker step each clone,
 *       commit and push.
 * </ul>
 *
 * <p><b>The two stories use different repositories, and that is the namespacing this catalogue runs
 * on.</b> A bump holds its (repository, group) lock until it ends, so two stories sharing one would
 * have the second answered 409 by the first's leftovers — the hazard {@code InventoryReset} solves
 * for the surefire suite, which a launched process has no equivalent of.
 *
 * <p><b>What closes a bump is the sweep, and nothing else.</b> {@code BumpService.dispatch} sends
 * the trigger and returns with the row RUNNING; the poll that reads the ci run and writes the
 * verdict is only ever called from {@code BumpPollSchedule}. {@link StoryProfile} therefore leaves
 * the scheduler on and silences every other timer at its own key, so the only background work that
 * can draw an arrow into a diagram is work these two stories are waiting for.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class BumpIT {

  static final String CATEGORY = "the bump";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String PUSHED = "An operator asks for a group's upgrades and qits-ci pushes the branch";

  static final String PUSHED_SLUG = Slugs.slug(PUSHED);

  static final String UNMOVED = "A green run that moved nothing is not a success";

  static final String UNMOVED_SLUG = Slugs.slug(UNMOVED);

  /**
   * The ids the two stories generated, kept so {@code @AfterAll} can pin that neither reached the
   * published bundle. A row id is a per-run value by definition: a note carrying one would move the
   * story's own hash on every build, and the only symptom is a hash that never settles.
   */
  private static String pushedBumpId;

  private static String unmovedBumpId;

  @BeforeAll
  static void tapEverySideOfThisService() {
    StoryNetwork.install();
  }

  @UserStory(value = PUSHED, category = CATEGORY)
  @UserStoryDescription(
      """
      An operator has read what is out of date and presses Bump on one group. What this service
      does with that is compose a payload — the group's pending changes, each naming a manifest, a
      location and the two versions — and hand it to qits-ci under the bump row's own id, which is
      the dedupe key that makes a retry record no second run. It answers 202 at once, because
      applying the changes is a CI run in somebody else's pipeline: a clone, an edit, a push. It
      reads the branch's head before the trigger and again when the run ends, and only the head,
      never a commit count — one bump is up to two commits. While the run is going, a second
      request for the same group is refused: two runs writing one branch would make the second a
      non-ff rejection at best. When the run passes and the branch has moved, the row ends
      SUCCEEDED and carries the changes it sent, which is the audit trail — not what is pending
      now, which by then is a different question — and then it hands the branch on, asking
      qits-workspaces' release door for a release request pinned to exactly the head it watched
      land, rather than leaving the branch for somebody to notice.
      """)
  @UserflowRunsAfter(InventoryIT.class)
  @Order(1)
  void aBumpIsAPayloadForSomebodyElsesPipeline(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    StoryPeers ci = StoryPeers.attach(StoryTarget.CI);
    StoryPeers githost = StoryPeers.attach(StoryTarget.GITHOST);
    StoryPeers workspaces = StoryPeers.attach(StoryTarget.WORKSPACES);

    // THE RELEASE DOOR, armed before anything is triggered. The sweep closes the bump and asks the
    // door on the same worker task, so a door armed after the run went green would be a race the
    // story would lose about one time in ten.
    workspaces.json(
        StoryTarget.RELEASE_DOOR,
        "{\"requestId\":\"" + StoryCatalog.RELEASE_REQUEST + "\",\"state\":\"PENDING\","
            + "\"branch\":\"" + StoryCatalog.BRANCH + "\",\"commitSha\":\""
            + StoryCatalog.BUMPED_SHA + "\",\"detail\":null}");

    // qits-ci accepts the trigger and names one run, which is still going. The branch is armed
    // NOWHERE: an unregistered path is a 404, which is exactly what the git host says about a
    // branch nobody has pushed — and that 404 is what the head after the run is compared against.
    ci.json(
        StoryCatalog.TRIGGER_PATH,
        "{\"eventId\":\"e-pushed\",\"runIds\":[\"" + StoryCatalog.RUN + "\"],"
            + "\"repositoriesRead\":1,\"repositoriesSkipped\":[]}");
    ci.json(
        StoryCatalog.runPath(StoryCatalog.RUN),
        "{\"id\":\"" + StoryCatalog.RUN + "\",\"status\":\"RUNNING\"}");

    String bumps =
        StoryTarget.REPOSITORIES
            + "/"
            + StoryCatalog.REPOSITORY
            + "/groups/"
            + StoryCatalog.DEFAULT_GROUP
            + "/bumps";
    pushedBumpId =
        StoryIdentities.operator(given())
            .contentType(ContentType.JSON)
            .post(bumps)
            .then()
            .statusCode(202)
            .contentType(ContentType.JSON)
            .extract()
            .path("id");
    story
        .note("Bump answers 202 with the id of a row, and does not wait: what applies the changes"
            + " is a CI run in somebody else's pipeline")
        .as("bump-accepted");

    StoryWaits.bumpReaches(pushedBumpId, "RUNNING");

    // --- WHAT qits-ci WAS REALLY HANDED ---------------------------------------------------------
    //
    // Read off the wire rather than out of this service's own row, because "the payload is right"
    // is a claim about what left this process. The event id IS the bump row id: qits-ci dedupes on
    // (event id, repository, config path), so a dispatch whose answer was lost records no second
    // run when the sweep sends it again.
    List<String> triggers = ci.bodiesFor(StoryCatalog.TRIGGER_PATH);
    assertEquals(1, triggers.size(), "the bump must have been triggered exactly once");
    String payload = triggers.getFirst();
    assertTrue(
        payload.contains("\"name\":\"" + CiClient.EVENT_NAME + "\""),
        "the trigger must name the event the platform pipeline selects on: " + payload);
    assertTrue(
        payload.contains("\"eventId\":\"" + pushedBumpId + "\""),
        "the event id must BE the bump row id, which is qits-ci's dedupe key: " + payload);
    assertTrue(
        payload.contains("\"repository\":\"" + StoryCatalog.REPOSITORY + "\""),
        "the payload names the repository by its public name: " + payload);
    assertTrue(
        payload.contains("\"branch\":\"" + StoryCatalog.BRANCH + "\"")
            && payload.contains("\"baseRef\":\"main\""),
        "a group's name IS its branch, cut from the repository's main branch: " + payload);
    // One change, in full, because the shape is the contract three repositories build against.
    assertTrue(
        payload.contains("\"location\":\"property:qits.eventstream.version\"")
            && payload.contains("\"from\":\"2026.811.1\"")
            && payload.contains("\"to\":\"2026.821.3\"")
            && payload.contains("\"manifestPath\":\"pom.xml\""),
        "a change names a file, a location and two versions: " + payload);
    // AND THE GROUPING IS HONOURED ON THE WIRE. @angular/core is pending too, and it belongs to the
    // group this repository declared for it — a payload carrying it would put a change on a branch
    // its author configured against.
    assertFalse(
        payload.contains("@angular/core"),
        "a change may only travel on the branch its own group names: " + payload);
    story
        .note("what qits-ci was handed is a list of edits — a file, a location and two versions"
            + " each — under the bump row's own id, which is the dedupe key that makes a retry"
            + " record no second run")
        .as("the-payload-is-the-decision");
    story
        .note("and the grouping is honoured on the wire: the pins this repository configured onto"
            + " another branch are not in this payload")
        .as("groups-are-branches");

    // --- ONE BRANCH, ONE WRITER -----------------------------------------------------------------
    StoryIdentities.operator(given())
        .contentType(ContentType.JSON)
        .post(bumps)
        .then()
        .statusCode(409)
        .contentType(ContentType.JSON)
        .body("message", not(nullValue()));
    story
        .note("a second request for the same group while one is going is refused 409 — two runs"
            + " writing one branch would make the second a non-ff rejection at best")
        .as("one-branch-one-writer");

    // --- THE RUN ENDS, AND THE BRANCH MOVED -----------------------------------------------------
    githost.json(
        StoryCatalog.tree(StoryCatalog.REPOSITORY, StoryCatalog.BRANCH),
        "{\"entries\":[]}",
        Map.of("Git-Commit-Sha", StoryCatalog.BUMPED_SHA));
    ci.json(
        StoryCatalog.runPath(StoryCatalog.RUN),
        "{\"id\":\"" + StoryCatalog.RUN + "\",\"status\":\"SUCCESS\"}");
    assertEquals("SUCCEEDED", StoryWaits.bump(pushedBumpId), "a green run on a moved branch is a success");

    StoryIdentities.operator(given())
        .get(StoryTarget.BUMPS + "/" + pushedBumpId)
        .then()
        .statusCode(200)
        .body("repository", equalTo(StoryCatalog.REPOSITORY))
        .body("group", equalTo(StoryCatalog.DEFAULT_GROUP))
        .body("branch", equalTo(StoryCatalog.BRANCH))
        .body("trigger", equalTo("MANUAL"))
        .body("status", equalTo("SUCCEEDED"))
        .body("ciRunStatus", equalTo("SUCCESS"))
        .body("ciRunIds", equalTo(List.of(StoryCatalog.RUN)))
        // Every bump row records WHICH environment's ci ran it, so a second one would be a config
        // entry rather than a schema change.
        .body("environment", not(nullValue()))
        // The pipeline file in the wrapper that answers MaintenanceBump. The same for every bump,
        // so the row carries it as a constant rather than reading it back per run.
        .body("configPath", equalTo(CiClient.CONFIG_PATH))
        // THE AUDIT TRAIL: what was SENT, not what is pending now. By the time anyone reads a bump
        // the pins have moved and the latest versions have moved again.
        .body("changes.size()", greaterThan(0))
        .body("changes.name", hasItem("eu.wohlben.qits:qits-eventstream"))
        .body("changes.name", not(hasItem("@angular/core")))
        .body("finishedAt", not(nullValue()))
        .body("message", containsString(StoryCatalog.BRANCH))
        // AND THE BRANCH WAS HANDED ON. A branch nobody asks about is a branch that sits there.
        .body("releaseRequestId", equalTo(StoryCatalog.RELEASE_REQUEST));
    story
        .note("the run passed and the branch moved, so the row ends SUCCEEDED — with the changes it"
            + " SENT, which is the audit trail: what is pending now is a different question")
        .as("the-branch-was-pushed");

    // --- AND THE ASK THAT FOLLOWS IT ------------------------------------------------------------
    //
    // Read off the wire for the same reason the trigger is: "the branch was handed on" is a claim
    // about what left this process. Nothing merged at this call — the door creates a release
    // REQUEST, which the quality gates settle afterwards — so what this proves is that the branch
    // stopped being this service's problem, not that it was released.
    List<String> asks = workspaces.bodiesFor(StoryTarget.RELEASE_DOOR);
    assertEquals(1, asks.size(), "the door must have been asked exactly once");
    String ask = asks.getFirst();
    assertTrue(
        ask.contains("\"branch\":\"" + StoryCatalog.BRANCH + "\""),
        "the ask names the branch this bump pushed: " + ask);
    assertTrue(
        ask.contains("\"summary\":\"bump(" + StoryCatalog.DEFAULT_GROUP + "): "),
        "the summary is the shape the bump's own commits carry: " + ask);
    // THE SHA IS THE ONE THE RUN PRODUCED, and it is the whole reason the ask is trustworthy: the
    // door otherwise arms the request with whatever the branch holds when it is asked, which is a
    // different commit the moment anybody pushes onto it.
    assertTrue(
        ask.contains("\"expectedSha\":\"" + StoryCatalog.BUMPED_SHA + "\""),
        "the ask is pinned to exactly the head this bump observed: " + ask);
    story
        .note("and then this service asks qits-workspaces' release door for that branch — pinned"
            + " with the exact head the run produced, so the request can only ever be about the"
            + " commits this bump watched land")
        .as("the-branch-is-handed-on");

    StoryIdentities.operator(given())
        .queryParam("repository", StoryCatalog.REPOSITORY)
        .get(StoryTarget.BUMPS)
        .then()
        .statusCode(200)
        .body("size()", greaterThan(0))
        .body("[0].status", equalTo("SUCCEEDED"))
        .body("[0].changes.size()", greaterThan(0));
    story
        .note("and it is in the log an operator reads afterwards, with its change list beside it")
        .as("the-bump-log");

    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "the changes are frozen onto the bump row at REQUEST time and never recomputed");
  }

  @UserStory(value = UNMOVED, category = CATEGORY)
  @UserStoryDescription(
      """
      The ending nobody designs for and everybody gets. qits-ci ran the pipeline, every step
      passed, and the branch is exactly where it was — because the step read the files and found
      the versions already there. That happens whenever the pins moved between the scan that
      computed the changes and the run that applied them, or another bump got there first, and it
      is not a success: reporting SUCCEEDED would put a branch in a nightly list that nobody ever
      pushed. So the head is read before the trigger and again when the run ends, only the head is
      compared — one bump is up to two commits, so a service expecting one would report every
      mixed group as broken — and a green run over an unmoved branch ends NOTHING_TO_DO with the
      sentence that says so.
      """)
  @UserflowRunsAfter(InventoryIT.class)
  @Order(2)
  void aGreenRunOverAnUnmovedBranchIsNotASuccess(Interactions story, Network network) {
    NetworkCapture.actor(StoryIdentities.OPERATOR);
    StoryPeers ci = StoryPeers.attach(StoryTarget.CI);
    StoryPeers githost = StoryPeers.attach(StoryTarget.GITHOST);

    // The branch already exists and stays exactly where it is, before the run and after it. It is
    // the EXTERNAL half's branch: this repository pins one dependency and it is somebody else's.
    githost.json(
        StoryCatalog.tree(StoryCatalog.SECOND_REPOSITORY, StoryCatalog.EXTERNAL_BRANCH),
        "{\"entries\":[]}",
        Map.of("Git-Commit-Sha", StoryCatalog.UNMOVED_SHA));
    ci.json(
        StoryCatalog.TRIGGER_PATH,
        "{\"eventId\":\"e-unmoved\",\"runIds\":[\"" + StoryCatalog.SECOND_RUN + "\"],"
            + "\"repositoriesRead\":1,\"repositoriesSkipped\":[]}");
    ci.json(
        StoryCatalog.runPath(StoryCatalog.SECOND_RUN),
        "{\"id\":\"" + StoryCatalog.SECOND_RUN + "\",\"status\":\"SUCCESS\"}");

    unmovedBumpId =
        StoryIdentities.operator(given())
            .contentType(ContentType.JSON)
            .post(
                StoryTarget.REPOSITORIES
                    + "/"
                    + StoryCatalog.SECOND_REPOSITORY
                    + "/groups/"
                    + StoryCatalog.EXTERNAL_GROUP
                    + "/bumps")
            .then()
            .statusCode(202)
            .extract()
            .path("id");
    story
        .note("a second repository, the external half of its grouping with something pending, and"
            + " a branch that already exists at a commit this service records before it triggers"
            + " anything")
        .as("the-head-before");

    assertEquals(
        "NOTHING_TO_DO",
        StoryWaits.bump(unmovedBumpId),
        "a green run that moved no branch must not be reported as a success");

    StoryIdentities.operator(given())
        .get(StoryTarget.BUMPS + "/" + unmovedBumpId)
        .then()
        .statusCode(200)
        .body("repository", equalTo(StoryCatalog.SECOND_REPOSITORY))
        .body("status", equalTo("NOTHING_TO_DO"))
        // The ci run itself PASSED. The two facts are independent, and reading them as one is the
        // mistake this outcome exists to prevent.
        .body("ciRunStatus", equalTo("SUCCESS"))
        .body("ciRunIds", equalTo(List.of(StoryCatalog.SECOND_RUN)))
        .body("message", containsString("did not move"))
        // The changes are still on the row: the payload went out and is what a person reads to see
        // what the step decided against.
        .body("changes.size()", greaterThan(0));
    story
        .note("the run PASSED and the branch is where it was, so the outcome is NOTHING_TO_DO and"
            + " the row says which of the two facts it is — the changes it sent are still on it")
        .as("passed-and-unmoved");

    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "the head before the run is recorded, and the verdict is written against it");
  }

  @AfterAll
  static void bothBumpStoriesAreComplete() {
    String branchWire = StoryCatalog.treeWire(StoryCatalog.REPOSITORY, StoryCatalog.BRANCH);
    String secondBranchWire =
        StoryCatalog.treeWire(StoryCatalog.SECOND_REPOSITORY, StoryCatalog.EXTERNAL_BRANCH);
    String bumpsPath =
        StoryTarget.REPOSITORIES
            + "/"
            + StoryCatalog.REPOSITORY
            + "/groups/"
            + StoryCatalog.DEFAULT_GROUP
            + "/bumps";

    // --- the branch moved -----------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, PUSHED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "bump-accepted");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "the-payload-is-the-decision");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "groups-are-branches");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "one-branch-one-writer");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "the-branch-was-pushed");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "the-branch-is-handed-on");
    ReportAssertions.assertStepId(CATEGORY_SLUG, PUSHED_SLUG, "the-bump-log");

    in(PUSHED_SLUG, "POST " + bumpsPath + " -> 202");
    // THE REFUSAL IS ITS OWN ARROW, because a status is half of what an edge says.
    in(PUSHED_SLUG, "POST " + bumpsPath + " -> 409");
    in(PUSHED_SLUG, "GET " + StoryTarget.BUMPS + "/" + StoryTarget.ID + " -> 200");
    in(PUSHED_SLUG, "GET " + StoryTarget.BUMPS + " -> 200");

    // The branch head, read twice: a 404 before the trigger and a commit after the run. Those two
    // labels ARE the comparison this story is about.
    out(PUSHED_SLUG, StoryTarget.GITHOST, "GET " + branchWire + " -> 404");
    out(PUSHED_SLUG, StoryTarget.GITHOST, "GET " + branchWire + " -> 200");
    out(PUSHED_SLUG, StoryTarget.CI, "POST " + StoryCatalog.TRIGGER_PATH + " -> 200");
    out(
        PUSHED_SLUG,
        StoryTarget.CI,
        "GET " + StoryCatalog.runPath(StoryCatalog.RUN) + " -> 200");
    // THE SECOND THING THIS SERVICE MAKES HAPPEN ANYWHERE ELSE. The query half is on the label
    // because the door's addressing IS the public identity of the repository — the pair a clone
    // spells — and a label without it would not say which repository was handed on.
    out(
        PUSHED_SLUG,
        StoryTarget.WORKSPACES,
        "POST "
            + StoryTarget.RELEASE_DOOR
            + "?projectId=" + StoryCatalog.PROJECT
            + "&repositoryName=" + StoryCatalog.REPOSITORY
            + " -> 200");

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        PUSHED_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "the changes are frozen onto the bump row at REQUEST time and never recomputed");

    // THE DESIGN, ASSERTED AS A SHAPE. Four requests in; out, one trigger, one run read, two head
    // reads, one release ask and a row. THIS SERVICE PUSHED NOTHING — there is no arrow from it to
    // any repository, because there is no such call in it to make. An eleventh edge would be this
    // process having grown a way to touch somebody else's tree, and no presence check could see it.
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, PUSHED_SLUG, 10);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, PUSHED_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
    // A bump reads no manifest and asks no registry: the changes were frozen at REQUEST time, out
    // of an inventory a scan wrote. Recomputing at dispatch would not be the list the operator saw.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, PUSHED_SLUG, StoryTarget.PROJECTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, PUSHED_SLUG, StoryTarget.ARTIFACTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, PUSHED_SLUG, StoryTarget.MIRROR);
    // The row id is generated per run and reaches no label, no note and no rendering.
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, PUSHED_SLUG, pushedBumpId);

    // --- the branch did not ---------------------------------------------------------------------
    ReportAssertions.assertComplete(CATEGORY_SLUG, UNMOVED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, UNMOVED_SLUG, "the-head-before");
    ReportAssertions.assertStepId(CATEGORY_SLUG, UNMOVED_SLUG, "passed-and-unmoved");

    in(
        UNMOVED_SLUG,
        "POST "
            + StoryTarget.REPOSITORIES
            + "/"
            + StoryCatalog.SECOND_REPOSITORY
            + "/groups/"
            + StoryCatalog.EXTERNAL_GROUP
            + "/bumps -> 202");
    in(UNMOVED_SLUG, "GET " + StoryTarget.BUMPS + "/" + StoryTarget.ID + " -> 200");
    // ONE LABEL FOR BOTH READS, and that is the point rather than an accident: the head before the
    // trigger and the head after the run are the same answer, which is what NOTHING_TO_DO means.
    out(UNMOVED_SLUG, StoryTarget.GITHOST, "GET " + secondBranchWire + " -> 200");
    out(UNMOVED_SLUG, StoryTarget.CI, "POST " + StoryCatalog.TRIGGER_PATH + " -> 200");
    out(
        UNMOVED_SLUG,
        StoryTarget.CI,
        "GET " + StoryCatalog.runPath(StoryCatalog.SECOND_RUN) + " -> 200");

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        UNMOVED_SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "the head before the run is recorded, and the verdict is written against it");

    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, UNMOVED_SLUG, 6);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, UNMOVED_SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, UNMOVED_SLUG, StoryTarget.PROJECTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, UNMOVED_SLUG, StoryTarget.ARTIFACTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, UNMOVED_SLUG, StoryTarget.MIRROR);
    // AND NO RELEASE WAS ASKED FOR. Nothing was pushed, so there is nothing to hand on — which is
    // the one thing that separates this ending from the other on the far side of this service.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, UNMOVED_SLUG, StoryTarget.WORKSPACES);
    ReportAssertions.assertNotLeaked(CATEGORY_SLUG, UNMOVED_SLUG, unmovedBumpId);
  }

  private static void in(String slug, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG,
        slug,
        NetworkEdge.HTTP,
        StoryIdentities.OPERATOR,
        StoryTarget.SERVICE,
        label);
  }

  private static void out(String slug, String peer, String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, slug, NetworkEdge.HTTP, StoryTarget.SERVICE, peer, label);
  }
}
