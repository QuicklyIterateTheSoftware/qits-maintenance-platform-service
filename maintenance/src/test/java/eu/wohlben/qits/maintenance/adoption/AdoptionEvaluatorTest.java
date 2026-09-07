package eu.wohlben.qits.maintenance.adoption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>The verdict at each hop: ADOPTED or PENDING, and which release of theirs proves it.</b>
 *
 * <p>The rules here are the retired release trains' match rules, kept verbatim because they were
 * the half of that feature that was right — a bill of materials is the evidence, the comparison is
 * inclusive and in the ecosystem's own order, and a component this service cannot map never matches.
 * What is new is the CHAIN: the evaluation walks the closure, and each ADOPTED hop's own release
 * becomes the requirement for the hop behind it.
 *
 * <p><b>Every fixture name carries a uuid</b>, because this module has no {@code InventoryReset} and
 * the walk reads the whole inventory. See {@code DownstreamResolverTest}, which makes the same
 * argument at more length.
 */
@QuarkusTest
class AdoptionEvaluatorTest {

  private static final Instant MARCH = Instant.parse("2026-03-01T10:00:00Z");
  private static final Instant APRIL = Instant.parse("2026-04-01T10:00:00Z");
  private static final Instant MAY = Instant.parse("2026-05-01T10:00:00Z");
  private static final Instant JUNE = Instant.parse("2026-06-01T10:00:00Z");

  @Inject MaintenanceStore store;

  @Inject AdoptionEvaluator evaluator;

  private String run;

  @BeforeEach
  void aRunOfItsOwn() {
    run = "-" + UUID.randomUUID().toString().substring(0, 8);
  }

  // --- the fixture ------------------------------------------------------------------------------

  private void scanned(String repository, RepositoryArchetype archetype, ParsedPin... pins) {
    store.replaceInventory(
        repository,
        "qits",
        null,
        archetype == null ? null : archetype.name(),
        "main",
        RepositoryStatus.OK,
        "sha",
        null,
        List.of(pins),
        List.of(),
        GroupSource.DEFAULT,
        candidate -> PinKind.INTERNAL,
        Instant.now());
  }

  private static ParsedPin pin(Ecosystem ecosystem, String name) {
    return ParsedPin.of(
        ecosystem,
        ecosystem == Ecosystem.NPM ? "package.json" : "pom.xml",
        name,
        "0.0.1",
        null,
        "dependency:" + name);
  }

  /** A release with no document read yet — PENDING, and evidence of nothing. */
  private UUID releasedWithoutADocument(
      String repository, Ecosystem ecosystem, String name, String version, Instant when) {
    return store.upsertArtifact(ecosystem, name, version, repository, when);
  }

  /** A release whose bill of materials was read, naming whatever is passed. */
  private void released(
      String repository,
      Ecosystem ecosystem,
      String name,
      String version,
      Instant when,
      ParsedSbom.Component... components) {
    UUID id = releasedWithoutADocument(repository, ecosystem, name, version, when);
    store.replaceGraph(id, List.of(components), List.of(), Instant.now());
  }

  private static ParsedSbom.Component contains(Ecosystem ecosystem, String name, String version) {
    return new ParsedSbom.Component("c-" + name, null, ecosystem, name, version, true);
  }

  private static AdoptionEvaluator.Adopter adopter(
      AdoptionEvaluator.Journey journey, String repository) {
    return journey.adopters().stream()
        .filter(row -> repository.equals(row.repository()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(repository + " is not in the journey"));
  }

  // --- the comparison ---------------------------------------------------------------------------

  /**
   * <b>Inclusive, and in the ecosystem's OWN order.</b> A consumer that took the released version
   * has adopted it and one that skipped straight past it has adopted it too; a consumer below it is
   * still carrying the old copy, which is precisely the state this question exists to show.
   *
   * <p>The two "past" cases are chosen so a string comparison gets them wrong: {@code 2026.821.10}
   * is lexically BELOW {@code 2026.821.9} and numerically above it, and an npm prerelease sorts
   * below the release it is a candidate for while sorting above it as text.
   */
  @Test
  void theComparisonIsInclusiveAndUsesTheEcosystemsOwnOrder() {
    String library = "qits-order-lib" + run;
    String mavenPackage = "eu.wohlben.qits:qits-order" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.MAVEN, mavenPackage, "2026.821.9", MARCH);

    // Exactly the released version: adopted.
    String exact = "qits-order-exact" + run;
    scanned(exact, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, mavenPackage));
    released(
        exact,
        Ecosystem.MAVEN,
        "eu.wohlben.qits:exact" + run,
        "1.0.0",
        APRIL,
        contains(Ecosystem.MAVEN, mavenPackage, "2026.821.9"));

    // Past it, and lexically BELOW it: adopted, because the order is maven's.
    String past = "qits-order-past" + run;
    scanned(past, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, mavenPackage));
    released(
        past,
        Ecosystem.MAVEN,
        "eu.wohlben.qits:past" + run,
        "1.0.0",
        APRIL,
        contains(Ecosystem.MAVEN, mavenPackage, "2026.821.10"));

    // Behind it: pending, which is the whole point of asking.
    String behind = "qits-order-behind" + run;
    scanned(behind, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, mavenPackage));
    released(
        behind,
        Ecosystem.MAVEN,
        "eu.wohlben.qits:behind" + run,
        "1.0.0",
        APRIL,
        contains(Ecosystem.MAVEN, mavenPackage, "2026.820.1"));

    AdoptionEvaluator.Journey journey = evaluator.of(library, "2026.821.9");

    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(journey, exact).state());
    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(journey, past).state());
    assertEquals(AdoptionEvaluator.State.PENDING, adopter(journey, behind).state());
  }

  /** And npm's order is semver's, where a release candidate is BELOW the release it precedes. */
  @Test
  void anNpmPrereleaseIsBelowTheReleaseItIsACandidateFor() {
    String library = "qits-semver-lib" + run;
    String pkg = "@qits/semver" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "21.0.0", MARCH);

    String consumer = "qits-semver-consumer" + run;
    scanned(consumer, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, pkg));
    released(
        consumer,
        Ecosystem.NPM,
        "@qits/semver-consumer" + run,
        "1.0.0",
        APRIL,
        // Text says this is "greater than" 21.0.0. Semver says it is the candidate that came first.
        contains(Ecosystem.NPM, pkg, "21.0.0-rc.1"));

    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(evaluator.of(library, "21.0.0"), consumer).state());
  }

  /**
   * <b>A component with no ecosystem never matches.</b> {@code pkg:golang/…},
   * {@code pkg:generic/…}, a document with no purl at all: stored, shown on the repository page,
   * and compared with nothing. A name in a world this platform does not inventory means something
   * else.
   */
  @Test
  void aComponentWithNoEcosystemIsNeverEvidenceOfAnything() {
    String library = "qits-unmapped-lib" + run;
    String pkg = "eu.wohlben.qits:qits-unmapped" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.MAVEN, pkg, "1.0.0", MARCH);

    String consumer = "qits-unmapped-consumer" + run;
    // The pin is what puts it in the closure at all; the document is what fails to close it.
    scanned(consumer, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, pkg));
    released(
        consumer,
        Ecosystem.MAVEN,
        "eu.wohlben.qits:unmapped-consumer" + run,
        "2.0.0",
        APRIL,
        new ParsedSbom.Component("c-1", "pkg:golang/x/y@9.9.9", null, pkg, "9.9.9", true));

    AdoptionEvaluator.Journey journey = evaluator.of(library, "1.0.0");

    assertEquals(AdoptionEvaluator.State.PENDING, adopter(journey, consumer).state());
    assertNull(adopter(journey, consumer).adoptedVersion());
  }

  /**
   * <b>A release whose document has not been read is evidence of nothing — including of a
   * NON-adoption.</b> A row that is PENDING, MISSING or FAILED holds no components, so the honest
   * answer stays PENDING rather than becoming "they did not take it".
   */
  @Test
  void aReleaseWithNoIngestedDocumentLeavesTheAnswerPending() {
    String library = "qits-nodoc-lib" + run;
    String pkg = "eu.wohlben.qits:qits-nodoc" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.MAVEN, pkg, "1.0.0", MARCH);

    String consumer = "qits-nodoc-consumer" + run;
    scanned(consumer, RepositoryArchetype.SERVICE, pin(Ecosystem.MAVEN, pkg));
    releasedWithoutADocument(
        consumer, Ecosystem.MAVEN, "eu.wohlben.qits:nodoc-consumer" + run, "2.0.0", APRIL);

    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(evaluator.of(library, "1.0.0"), consumer).state());
  }

  // --- which release is reported ------------------------------------------------------------------

  /**
   * <b>THE EARLIEST MATCHING RELEASE, BY {@code occurred_at}.</b> A library taken in April and
   * shipped in every release since was adopted in April; the newest release carrying it is not when
   * it arrived. And the version reported is the ADOPTER's own — it is half of the address of the
   * release request that adoption opened.
   */
  @Test
  void theEarliestReleaseThatCarriesItIsTheAdoptionAndItIsTheAdoptersOwnVersion() {
    String library = "qits-earliest-lib" + run;
    String pkg = "@qits/earliest" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "2026.905.1", MARCH);

    String consumer = "qits-earliest-consumer" + run;
    String consumerPackage = "@qits/earliest-consumer" + run;
    scanned(consumer, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, pkg));
    // Three releases of the consumer, two of them carrying it. The one in JUNE is the newest and is
    // deliberately written first, so an implementation that took "whatever came back first" fails.
    released(
        consumer, Ecosystem.NPM, consumerPackage, "3.0.0", JUNE,
        contains(Ecosystem.NPM, pkg, "2026.905.4"));
    released(
        consumer, Ecosystem.NPM, consumerPackage, "2.0.0", MAY,
        contains(Ecosystem.NPM, pkg, "2026.905.1"));
    released(
        consumer, Ecosystem.NPM, consumerPackage, "1.0.0", APRIL,
        contains(Ecosystem.NPM, pkg, "2026.900.1"));

    AdoptionEvaluator.Adopter adopted =
        adopter(evaluator.of(library, "2026.905.1"), consumer);

    assertEquals(AdoptionEvaluator.State.ADOPTED, adopted.state());
    assertEquals("2.0.0", adopted.adoptedVersion(), "the consumer's OWN release, and the first one");
    assertEquals(MAY, adopted.adoptedAt());
  }

  // --- the chain ----------------------------------------------------------------------------------

  /**
   * <b>Each ADOPTED hop's own release is the requirement for the hop behind it</b>, and a PENDING
   * hop stops the chain there — there is no version of it to require yet. A service cannot be
   * shipping a library through a frontend that has not shipped the library.
   */
  @Test
  void aPendingParentLeavesItsChildrenPendingAndAnAdoptedOneCarriesTheChainOn() {
    String library = "qits-chain-lib" + run;
    String libraryPackage = "@qits/chain-lib" + run;
    String frontend = "qits-chain-frontend" + run;
    String frontendPackage = "@qits/chain-frontend" + run;
    String service = "qits-chain-service" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, libraryPackage, "2026.905.1", MARCH);

    // The frontend has NOT taken it: its only release carries an older copy.
    scanned(frontend, RepositoryArchetype.FRONTEND, pin(Ecosystem.NPM, libraryPackage));
    released(
        frontend, Ecosystem.NPM, frontendPackage, "1.0.0", APRIL,
        contains(Ecosystem.NPM, libraryPackage, "2026.900.1"));

    // …and the service is carrying the frontend's bundle at a version far beyond anything, which
    // must NOT make it adopted: what it is carrying is a frontend that never took the library.
    scanned(service, RepositoryArchetype.SERVICE, pin(Ecosystem.NPM, frontendPackage));
    released(
        service, Ecosystem.MAVEN, "eu.wohlben.qits:chain-service" + run, "9.0.0", MAY,
        contains(Ecosystem.NPM, frontendPackage, "99.0.0"));

    AdoptionEvaluator.Journey blocked = evaluator.of(library, "2026.905.1");
    assertEquals(AdoptionEvaluator.State.PENDING, adopter(blocked, frontend).state());
    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(blocked, service).state(),
        "a child of a PENDING chain has no requirement to have met");
    assertEquals(2, adopter(blocked, service).depth());

    // Now the frontend releases with it. The SAME service release closes, because the frontend's
    // adopting version (2.0.0) is now the requirement and the service is carrying 99.0.0.
    released(
        frontend, Ecosystem.NPM, frontendPackage, "2.0.0", JUNE,
        contains(Ecosystem.NPM, libraryPackage, "2026.905.1"));

    AdoptionEvaluator.Journey travelled = evaluator.of(library, "2026.905.1");
    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(travelled, frontend).state());
    assertEquals("2.0.0", adopter(travelled, frontend).adoptedVersion());
    assertEquals(AdoptionEvaluator.State.ADOPTED, adopter(travelled, service).state());
    assertEquals("9.0.0", adopter(travelled, service).adoptedVersion());
  }

  // --- the release nobody has heard of --------------------------------------------------------------

  /**
   * <b>There is no "no such release", and that is the change from the trains.</b> The old
   * {@code /trains/by-release} answered 404 when a release had opened no station — a fact about the
   * log rather than about the release. An unknown release simply published no coordinate anybody
   * could be carrying, so the closure is real and every row of it is PENDING.
   */
  @Test
  void aReleaseThisServiceNeverHeardOfIsAnEmptyPackageListAndAPendingClosure() {
    String library = "qits-unknown-lib" + run;
    String pkg = "@qits/unknown" + run;
    String consumer = "qits-unknown-consumer" + run;

    scanned(library, RepositoryArchetype.LIBRARY);
    released(library, Ecosystem.NPM, pkg, "1.0.0", MARCH);
    scanned(consumer, RepositoryArchetype.SERVICE, pin(Ecosystem.NPM, pkg));
    released(
        consumer, Ecosystem.NPM, "@qits/unknown-consumer" + run, "2.0.0", APRIL,
        contains(Ecosystem.NPM, pkg, "1.0.0"));

    AdoptionEvaluator.Journey journey = evaluator.of(library, "2026.999.1");

    assertTrue(journey.packages().isEmpty(), "that version published nothing this service knows of");
    assertEquals("2026.999.1", journey.version());
    assertEquals(
        AdoptionEvaluator.State.PENDING,
        adopter(journey, consumer).state(),
        "the closure is real; nothing in it can be carrying a version that published nothing");
  }
}
