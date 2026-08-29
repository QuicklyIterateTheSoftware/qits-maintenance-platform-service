package eu.wohlben.qits.maintenance.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;

/**
 * The whole service as it is <b>packaged</b> — like {@link PackagedSurfaceIT} beside it, but with
 * the OIDC tenant <b>on</b>, which no {@code @QuarkusTest} here can prove.
 *
 * <p>The shipped tenant is {@code quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}},
 * and <b>no suite in this repository turns that gate on at all</b> — the test
 * application.properties says so in as many words ("the OIDC tenant is disabled because
 * qits.auth.machine.required defaults false"), which is what keeps a clone-alone {@code ./mvnw
 * verify} free of an issuer. The consequence is that the entire shipped {@code quarkus.oidc.*}
 * block — auth-server-url with {@code discovery-enabled=false} and {@code jwks-path=jwks} joined
 * onto it, the boot-time fetch that {@code connection-delay} retries, audience enforcement,
 * groups→roles mapping — is exercised NOWHERE. This is the one place it runs. The far side is
 * {@link MockIdp}, whose recordings make the interaction assertable on <b>both ends</b>.
 *
 * <p>It is also this repo's first <b>userflow</b>: the proof doubles as documentation, emitted
 * under {@code target/userstories/} with the interactions drawn as a sequence diagram. The stories
 * are browserless (no {@code Flow} parameter), so no Chromium is involved anywhere.
 *
 * <p><b>The route both stories drive is {@code GET /maintenance/api/repositories}</b>, and it is
 * chosen as the least side-effectful read this service has. Every route in the four controllers is
 * {@code @RolesAllowed({"qits:admin", "qits:system"})} — a person presses <i>Bump now</i> in a
 * browser and a machine may ask for the same thing — so the machine role reaches all of them and
 * the choice is about what the request DOES, not about what it takes:
 *
 * <ul>
 *   <li>it names nothing. It is the one guarded read with neither a path parameter nor a query
 *       parameter — {@code /repositories/{name}} names a repository, {@code /dependencies} takes a
 *       glob, {@code /bumps} takes a repository and a limit, and {@code /scans/{id}} and {@code
 *       /bumps/{id}} name a row;
 *   <li>it reads THIS service's own store and no peer. The whole reason the inventory is a store
 *       rather than a page that asks the git host on each load is that this question has to be
 *       answerable without one — and the profile below points every peer at a port nothing listens
 *       on, so a read that had needed the catalog, the git host or a registry could not have come
 *       back at all;
 *   <li>and it cannot write. The two calls that can are {@code POST /scans}, which starts a run
 *       that reads every repository on the platform, and {@code POST
 *       /repositories/{name}/groups/{group}/bumps}, which asks qits-ci to push a branch into
 *       somebody else's tree. Both are obvious other candidates and both are worse on every count.
 * </ul>
 *
 * <p><b>THERE IS NO CEILING TO SHOW ON THE ACCEPTED SIDE, and that is this repository's shape
 * rather than an omission.</b> qits-platform-system's namesake ends its accepted story on a door
 * the same bearer does <i>not</i> open, because a terminal socket there is {@code
 * @RolesAllowed("qits:admin")}. Here the two roles are named together on every route, deliberately
 * — a machine-only guard would lock the operator out of the button this service exists to offer —
 * so a machine bearer that reads the inventory may also request a bump. What bounds the credential
 * is the role SET, and that is what the denied story's third door proves.
 *
 * <p><b>ITs are skipped by default here and this one does NOT flip that.</b> {@code skipITs} is
 * {@code true} in the root pom because {@link PackagedSurfaceIT} is this module's other integration
 * test and a good half of it is about the CLIENT — the base href at the root, the deep links, the
 * fallback that must not swallow {@code /maintenance} — which the userflow pipeline deliberately
 * does not build ({@code -Dquarkus.quinoa=false}, since the qits-platform-spa-maintenance submodule
 * arrives EMPTY in a step container). A blanket {@code -DskipITs=false} would make that run red on
 * a test that is right. {@code .config/qits/ci-event-userflows.yml} names this class instead
 * ({@code -DskipITs=false "-Dit.test=TokenValidationBootstrapIT"}), which is also what keeps the
 * userflow pipeline about these stories and nothing else — and keeps the property's own meaning
 * ("run everything") intact for the {@code native} profile in service/pom.xml that sets it.
 */
@QuarkusIntegrationTest
@TestProfile(TokenValidationBootstrapIT.PackagedWithMockIdp.class)
public class TokenValidationBootstrapIT {

  static final String CATEGORY = "authentication";
  static final String ACCEPTED_SLUG =
      "on-start-the-dependency-inventory-fetches-the-platform-s-signing-keys";
  static final String DENIED_SLUG =
      "a-stranger-s-token-never-reads-the-platform-s-dependency-inventory";

  private static final String REPOSITORIES = "/maintenance/api/repositories";

  /**
   * {@link PackagedSurfaceIT.PackagedUnderTarget} — the embedded postgres handed over as the
   * platform's generic resource triple, every peer pointed at a dead loopback port, the bounded
   * call timeout and the clock switched off, all parked in system properties because a test profile
   * is instantiated in more than one classloader — <b>plus the two things these stories are
   * about</b>: the gate that turns the shipped OIDC tenant on, and where the idp is.
   *
   * <p>Extending rather than copying is deliberate. What a launched qits-platform-maintenance needs
   * in order to boot at all is one answer, it is written out at length over there (the datasource
   * arrives as {@code QITS_RESOURCE_DB_*}, which is the shipped indirection rather than the
   * datasource keys), and a second copy of it would be a second place for it to drift. What is
   * added here is only the seams these stories move.
   *
   * <p>The mock idp starts <b>before</b> the application, via {@link MockIdp#ensureStarted()},
   * which parks its coordinates (and its keypair) in system properties for the same classloader
   * reason — that is also how a story method's {@link MockIdp#attach()} reaches the very server the
   * launched process fetched its keys from.
   *
   * <p><b>Every key here is a RUNTIME key.</b> A packaged process takes its configuration as {@code
   * -D} arguments on a jar that was already built, so a build-time key would be silently ignored
   * and these tests would prove the opposite of what they say.
   *
   * <p><b>There is no telemetry or event-bus line to darken, and that is a fact about this
   * repository rather than an omission.</b> Nothing here depends on qits-eventstream and there is
   * no opentelemetry extension on the classpath. The dial-outs a boot makes are the JWKS fetch,
   * which is pointed at {@link MockIdp} below, and whatever the schedules would start — and those
   * are off in the inherited profile, so the only work a boot begins is {@code RestartRecovery}'s
   * bump sweep over an empty table, which reaches no peer because there is nothing in flight to
   * resume.
   */
  public static class PackagedWithMockIdp extends PackagedSurfaceIT.PackagedUnderTarget {

    /**
     * The audience this service enforces, and it is a LITERAL rather than a variable name — the
     * difference from qits-githost's IT, which hands its launched process {@code
     * QITS_AUTH_MACHINE_AUDIENCE} because the shipped expression there reads that variable. Here
     * {@code qits.auth.machine.audience=qits-platform-maintenance} is spelled out in {@code
     * application.properties} and {@code quarkus.oidc.token.audience} references it, so the
     * audience under test is the shipped one and there is no expression to feed. A deployment still
     * overrides it by environment.
     */
    static final String AUDIENCE = "qits-platform-maintenance";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());
      // THE GATE, and turning it on is the point: the shipped tenant is
      // quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}, so this one key is the
      // difference between a service that validates machine bearers and one that does not. The
      // application.properties block says what flipping it implies — with it on there IS a tenant,
      // and the tenant fetches a JWKS at boot — and this is where that is proved rather than
      // described. It also says there is no third state, which is why nothing else is set with it.
      overrides.put("qits.auth.machine.required", "true");
      // The one seam these stories move: where the idp is. Runtime key, so the packaged artifact is
      // otherwise exactly what ships — discovery stays off and jwks-path stays `jwks`, joined onto
      // this URL.
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
      return overrides;
    }
  }

  @UserStory(
      value = "On start, the dependency inventory fetches the platform's signing keys",
      category = "authentication")
  @UserStoryDescription(
      """
      A freshly deployed qits-platform-maintenance must validate service bearers before any
      caller arrives: at startup it fetches the signing keys (JWKS) from qits-platform-idp —
      discovery stays off, the path is configured — so the very first machine request is
      accepted. What that bearer then buys is this service's whole surface, and the read it
      opens here is the inventory itself: every repository on the platform with what each has
      pending. That answer is rows out of this service's own store, which is the point of
      keeping an inventory at all — the question "who is still on last month's release" has to
      be answerable without asking the git host anything.
      """)
  void serviceBootFetchesJwksAndAcceptsPlatformTokens(Interactions story) {
    MockIdp idp = MockIdp.attach();

    story.note(
        "qits-platform-maintenance starts with the OIDC tenant on, beside a reachable"
            + " qits-platform-idp");
    given().get("/maintenance/q/health/ready").then().statusCode(200);

    // End (a), the idp side: the JWKS was served during startup — before this story presented any
    // token at all. Readiness above is deliberately independent of that fetch — the shipped config
    // explains why: tying it to another service's would make a cold boot a question of ordering —
    // so a 200 there is not the claim. The recording is.
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story
        .happened("qits-platform-maintenance", "qits-platform-idp", "GET /idp/jwks (at startup)")
        .as("jwks-fetched");

    // End (b), this service's side: those keys are what token validation now runs on. A platform
    // peer's bearer (aud = this service, roles in `groups`) opens the inventory listing — nothing
    // named, nothing written, no peer asked.
    //
    // The counts are read out of the postgres this JVM handed the launched process, over JDBC and
    // BEFORE the request, so what is asserted is that the listing is that store's rows rather than
    // merely that something well-formed came back. A comparison rather than a fixed number on
    // purpose: PackagedSurfaceIT shares this database when both ITs run, and a scan that failed on
    // a dead catalog writes no repository row — the two facts have to agree either way.
    int stored = rows("mt_repository");
    int scansBefore = rows("mt_scan");
    int bumpsBefore = rows("mt_bump");
    String peerToken =
        idp.token()
            .subject("a-platform-service")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .mint();
    given()
        .header("Authorization", "Bearer " + peerToken)
        .get(REPOSITORIES)
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("$", hasSize(stored));
    story
        .happened(
            "a platform service",
            "qits-platform-maintenance",
            "GET /maintenance/api/repositories (Bearer, groups=[qits:system])")
        .as("inventory-served");

    // End (c), the store's own side, and it is what makes this the least side-effectful read this
    // service has rather than merely a plausible one. Two claims, both read back over JDBC:
    //
    //   - the read STARTED nothing. This service's other two machine-reachable calls queue work —
    //     a scan reads every repository on the platform, a bump asks qits-ci to push a branch into
    //     somebody else's tree — and each of them lands as a row before it does anything. Neither
    //     table moved, so the credential was spent on a read and on nothing else;
    //   - it asked no peer. Every address this service reads manifests and versions from —
    //     qits-projects, qits-githost, qits-ci, the three registries and the mirror — is a port
    //     nothing listens on in this profile, with the call timeout bounded to two seconds. The
    //     listing answered anyway, which is the inventory being a store rather than a page that
    //     asks the git host on each load.
    assertEquals(
        scansBefore, rows("mt_scan"), "reading the inventory must not have started a scan");
    assertEquals(
        bumpsBefore, rows("mt_bump"), "reading the inventory must not have requested a bump");
    story
        .happened(
            "qits-platform-maintenance",
            "its own inventory store",
            "the listing is read from the inventory tables: no peer asked, no work queued")
        .as("store-read");
  }

  @UserStory(
      value = "A stranger's token never reads the platform's dependency inventory",
      category = "authentication")
  @UserStoryDescription(
      """
      The flip side of trusting the platform's keys. A token signed by a key the published JWKS
      never carried, or minted for another service's audience, is refused at the door — however
      well-formed it looks: both are 401 and not 403, because the credential never became an
      identity and there is no caller to have been forbidden. A token addressed here and signed
      correctly but carrying a role this service has never heard of gets the other answer, 403 —
      it authenticated and covers nothing. There is no anonymous route in this service and there
      must never be one: what the inventory shows is every repository on the platform and what
      each of them pins, and the same credential that reads it can ask for a branch to be pushed
      into any of them.
      """)
  void aStrangersTokenIsRefused(Interactions story) {
    MockIdp idp = MockIdp.attach();

    String strangersToken =
        idp.token()
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:system")
            .signedByUnknownKey()
            .mint();
    given()
        .header("Authorization", "Bearer " + strangersToken)
        .get(REPOSITORIES)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-platform-maintenance",
            "GET /maintenance/api/repositories (token signed by an unknown key) -> 401")
        .as("unknown-key-refused");

    String wrongAudienceToken =
        idp.token().audience("some-other-service").groups("qits:system").mint();
    given()
        .header("Authorization", "Bearer " + wrongAudienceToken)
        .get(REPOSITORIES)
        .then()
        .statusCode(401);
    story
        .happened(
            "an impostor",
            "qits-platform-maintenance",
            "GET /maintenance/api/repositories (another service's audience) -> 401")
        .as("wrong-audience-refused");

    // The third door, and the one that proves the groups→roles mapping really ran rather than being
    // waved through: `qits:reader` is a real platform role and it is not one of the two every route
    // here names. Minted into a token addressed here it authenticates perfectly and still covers
    // nothing — which is the whole bound on a credential that, with the right role, would open the
    // bump button as well as the listing.
    String readerToken =
        idp.token()
            .subject("somebody-elses-service")
            .audience(PackagedWithMockIdp.AUDIENCE)
            .groups("qits:reader")
            .mint();
    given()
        .header("Authorization", "Bearer " + readerToken)
        .get(REPOSITORIES)
        .then()
        .statusCode(403);
    story
        .happened(
            "a caller with the wrong role",
            "qits-platform-maintenance",
            "GET /maintenance/api/repositories (Bearer, groups=[qits:reader]) -> 403")
        .as("wrong-role-refused");
  }

  /**
   * How many rows one of the store's tables holds, read the way {@link PackagedSurfaceIT} reads its
   * scan row back: over JDBC against the same embedded postgres the launched process was handed, so
   * the number does not come from the API that is under test.
   *
   * <p>The table name is a literal from this class and never anything a request carried, which is
   * why it is concatenated rather than bound — a {@code count(*)} has no parameter position for an
   * identifier.
   */
  private static int rows(String table) {
    String url = EmbeddedPg.url(PackagedSurfaceIT.DATABASE);
    try (Connection connection =
            DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        Statement sql = connection.createStatement();
        ResultSet found = sql.executeQuery("select count(*) from " + table)) {
      found.next();
      return found.getInt(1);
    } catch (Exception e) {
      throw new IllegalStateException("could not read " + table + " back", e);
    }
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    // The extension emits each report in its afterEach, so both are on disk before @AfterAll runs.
    ReportAssertions.assertComplete(CATEGORY, ACCEPTED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertInteraction(
        CATEGORY,
        ACCEPTED_SLUG,
        "qits-platform-maintenance",
        "qits-platform-idp",
        "GET /idp/jwks (at startup)");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "jwks-fetched");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "inventory-served");
    ReportAssertions.assertStepId(CATEGORY, ACCEPTED_SLUG, "store-read");

    ReportAssertions.assertComplete(CATEGORY, DENIED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "unknown-key-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-audience-refused");
    ReportAssertions.assertStepId(CATEGORY, DENIED_SLUG, "wrong-role-refused");
  }
}
