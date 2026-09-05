package eu.wohlben.qits.maintenance.stories.trains;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import eu.wohlben.qits.maintenance.stories.scan.ScanCycleIT;
import eu.wohlben.qits.maintenance.stories.support.StoryCatalog;
import eu.wohlben.qits.maintenance.stories.support.StoryIdentities;
import eu.wohlben.qits.maintenance.stories.support.StoryNetwork;
import eu.wohlben.qits.maintenance.stories.support.StoryProfile;
import eu.wohlben.qits.maintenance.stories.support.StoryTarget;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * <b>The journey surface over the launched artifact — and the honest bound on what a story here can
 * claim.</b>
 *
 * <p>A release train is opened by a {@code SoftwareRelease} frame arriving on the bus, and <b>a bus
 * frame cannot be driven into a launched artifact from out here</b>: the stream this catalogue runs
 * against is dark ({@code qits.eventstream.enabled=false} in {@link StoryProfile}, inherited from
 * the packaged profile), and there is no HTTP door that spawns a station — deliberately, because the
 * membership of a train is decided at the release and nowhere else. So the journey a train MAKES is
 * proved where it can be: {@code train/TrainSpawnTest}, {@code train/TrainEvaluationTest} and {@code
 * api/TrainApiTest}, which seed through the domain and read the same three routes with nodes on
 * them.
 *
 * <p>What IS reachable here — and is worth a story of its own — is the surface itself against a
 * <b>packaged process with a real identity contract</b>: that the routes are where the edge routes
 * them, that a release with no station is refused by name rather than answered with an empty page,
 * and that a cross-link missing half its key is refused rather than guessed at. None of that is
 * checkable in a {@code @QuarkusTest}, where the roles come from a dev user and the prefixes come
 * from the same config the test reads.
 *
 * <p><b>And the whole read asks nobody anything.</b> Five directed negatives hold while it happens,
 * which is the same claim {@code InventoryIT} makes and matters more here: the journey view is what
 * an operator opens the moment a release looks stuck, which is exactly when the platform is busy.
 */
@QuarkusIntegrationTest
@TestProfile(StoryProfile.class)
public class TrainJourneyIT {

  static final String CATEGORY = "release trains";

  static final String CATEGORY_SLUG = Slugs.slug(CATEGORY);

  static final String STORY = "An operator asks where a release got to, and no peer is asked";

  static final String SLUG = Slugs.slug(STORY);

  /**
   * An id that is not a uuid, so the label stays literal. A malformed id and an absent one are the
   * same question from the caller's side, which is the behaviour this pins.
   */
  private static final String NO_SUCH_TRAIN = "a-train-that-never-left";

  /** A version of the fixture repository that nothing in this catalogue ever released. */
  private static final String UNRELEASED_VERSION = "2026.905.99";

  @BeforeAll
  static void tapEverySideOfThisService() {
    StoryNetwork.install();
  }

  @UserStory(value = STORY, category = CATEGORY)
  @UserStoryDescription(
      """
      A release goes out and the estate is supposed to end up carrying it. Today that is a thing a
      person reconstructs by hand — one repository at a time, out of pins and published versions,
      with nothing anywhere saying whether the journey ever finished. A release train writes it
      down at the moment of the release, and this is the surface an operator reads it through:
      every recent journey, one journey in full, and the same journey addressed the way a release
      request holds it — a repository and a version. What a train IS gets decided on the bus, so
      this platform has none to show; what it shows instead is that the index answers rather than
      breaking, that a release with no station is refused BY NAME rather than dressed up as an
      empty journey, and that a cross-link missing half its key is refused rather than guessed
      into "the newest train of that repository". None of it asks anybody anything: a journey is
      rows, and a page that dialled qits-projects to draw one would be blank exactly when a
      release looked stuck.
      """)
  @UserflowRunsAfter(ScanCycleIT.class)
  void anOperatorAsksWhereAReleaseGotTo(Interactions story, Network network) {
    // The tap sees a request and never a narrative role, so the actor is named before the first
    // call.
    NetworkCapture.actor(StoryIdentities.OPERATOR);

    // THE INDEX. Nothing on this platform has released while these stories ran — the bus is dark —
    // so the honest answer is an empty page, and an empty page is an answer rather than a 404.
    StoryIdentities.operator(given())
        .get(StoryTarget.TRAINS)
        .then()
        .statusCode(200)
        .body("size()", equalTo(0));
    story
        .note("the journey index: every recent release and how far each got. It is empty here"
            + " because nothing released, and an empty index is an answer rather than a refusal")
        .as("the-journey-index");

    // THE CROSS-LINK, missing half its key. Answering "the newest train of that repository" would
    // be this service deciding which release the caller meant.
    StoryIdentities.operator(given())
        .queryParam("repository", StoryCatalog.REPOSITORY)
        .get(StoryTarget.TRAIN_BY_RELEASE)
        .then()
        .statusCode(400)
        .contentType(ContentType.JSON)
        .body("message", containsString("both a repository and a version"));
    story
        .note("the release cross-link takes a repository AND a version, and half a key is refused"
            + " rather than widened to the newest release of that repository")
        .as("half-a-key-is-not-a-guess");

    // AND A RELEASE WITH NO STATION. It is a 404 by name — told apart from a train that exists and
    // is empty, which is a journey of length zero and answers 200.
    StoryIdentities.operator(given())
        .queryParam("repository", StoryCatalog.REPOSITORY)
        .queryParam("version", UNRELEASED_VERSION)
        .get(StoryTarget.TRAIN_BY_RELEASE)
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("message", containsString(StoryCatalog.REPOSITORY))
        .body("message", containsString(UNRELEASED_VERSION));
    StoryIdentities.operator(given())
        .get(StoryTarget.TRAINS + "/" + NO_SUCH_TRAIN)
        .then()
        .statusCode(404)
        .contentType(ContentType.JSON)
        .body("message", containsString(NO_SUCH_TRAIN))
        // The platform's envelope is one key and the sentence in it — never a half-drawn journey,
        // and never the page: /maintenance is claimed out of the SPA fallback, so a mistyped
        // journey address is a refusal rather than an HTML document a JSON parser is handed.
        .body("nodes", nullValue());
    story
        .note("a release that has no station is 404 by name, and so is an id that is not one —"
            + " both are the same question from the caller's side, and neither is an empty journey")
        .as("no-station-is-said-not-implied");

    // THE ONE ARROW OUT, AND IT IS A CLAIM RATHER THAN EVIDENCE. Every answer above came out of the
    // store over a JDBC connection opened inside the launched process, where no tap of ours can
    // stand. What makes the story's title true is the five edges that are absent.
    network.declare(
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "a journey is rows: the trains, their nodes, and the catalog each node is addressed by");
  }

  @AfterAll
  static void theJourneyStoryIsComplete() {
    ReportAssertions.assertComplete(CATEGORY_SLUG, SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "the-journey-index");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "half-a-key-is-not-a-guess");
    ReportAssertions.assertStepId(CATEGORY_SLUG, SLUG, "no-station-is-said-not-implied");

    // The tap labels a path and a status, and both cross-link reads travel their key in the query —
    // so the two by-release calls are two edges because their ANSWERS differ, not their addresses.
    in("GET " + StoryTarget.TRAINS + " -> 200");
    in("GET " + StoryTarget.TRAIN_BY_RELEASE + " -> 400");
    in("GET " + StoryTarget.TRAIN_BY_RELEASE + " -> 404");
    in("GET " + StoryTarget.TRAINS + "/" + NO_SUCH_TRAIN + " -> 404");

    ReportAssertions.assertDeclaredEdge(
        CATEGORY_SLUG,
        SLUG,
        NetworkEdge.JDBC,
        StoryTarget.SERVICE,
        StoryTarget.STORE,
        "a journey is rows: the trains, their nodes, and the catalog each node is addressed by");

    // THE STORY'S TITLE, ASSERTED AS A SHAPE. The journey view is opened when a release looks
    // stuck, which is the worst moment to depend on the catalog being up — so every peer this
    // service has is a directed negative, and the count closes it.
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.PROJECTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.GITHOST);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.CI);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.ARTIFACTS);
    ReportAssertions.assertNoEdgesTo(CATEGORY_SLUG, SLUG, StoryTarget.MIRROR);
    ReportAssertions.assertEdgeCount(CATEGORY_SLUG, SLUG, 5);
    ReportAssertions.assertOnlyEdgesFrom(
        CATEGORY_SLUG, SLUG, List.of(StoryIdentities.OPERATOR, StoryTarget.SERVICE));
  }

  private static void in(String label) {
    ReportAssertions.assertEdge(
        CATEGORY_SLUG, SLUG, NetworkEdge.HTTP, StoryIdentities.OPERATOR, StoryTarget.SERVICE, label);
  }
}
