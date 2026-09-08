package eu.wohlben.qits.maintenance.adoption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * <b>What a release's own tree declared, and what is kept out of it.</b>
 *
 * <p>The seam is {@link ManifestScanner#pinsAt}: everything below the tag read — the four parsers,
 * the reactor, the discovery — is {@code ManifestScannerTest}'s subject and is stubbed here. What
 * this class is about is the three decisions the ledger itself makes: which pins are worth
 * recording, what happens when the git host does or does not hold the tag, and that recording twice
 * converges rather than accumulating.
 *
 * <p><b>Every fixture name carries a uuid</b>, for {@code DownstreamResolverTest}'s reason: this
 * module has no {@code InventoryReset} and the ledger is read by coordinate across the whole store.
 */
@QuarkusTest
class ReleaseLedgerTest {

  private static final Instant APRIL = Instant.parse("2026-04-01T10:00:00Z");
  private static final String SHA = "0011223344556677889900aabbccddeeff001122";

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  /** A scanner that answers a canned reading per revision, and records what it was asked. */
  private static final class Trees extends ManifestScanner {

    final Map<String, Pins> answers = new LinkedHashMap<>();
    final List<String> asked = new ArrayList<>();

    void holds(String repository, String revision, String sha, ParsedPin... pins) {
      answers.put(
          repository + " " + revision,
          new Pins(FileLookup.Status.FOUND, sha, List.of(pins), null));
    }

    void unreachable(String repository, String revision) {
      answers.put(
          repository + " " + revision,
          new Pins(FileLookup.Status.UNREACHABLE, null, List.of(), "the git host is not there"));
    }

    @Override
    public Pins pinsAt(String project, String name, String revision) {
      asked.add(project + "/" + name + " " + revision);
      return answers.getOrDefault(
          name + " " + revision, new Pins(FileLookup.Status.GONE, null, List.of(), null));
    }
  }

  private Trees trees;
  private ReleaseLedger ledger;
  private String run;

  @BeforeEach
  void aRunOfItsOwn() {
    run = "-" + UUID.randomUUID().toString().substring(0, 8);
    trees = new Trees();
    ledger = new ReleaseLedger();
    ledger.manifests = trees;
    ledger.store = store;
    ledger.config = config;
  }

  private static ParsedPin pin(Ecosystem ecosystem, String name, String version) {
    return ParsedPin.of(ecosystem, "manifest", name, version, null, "dependency:" + name);
  }

  private List<MaintenanceStore.ReleaseCarrier> declaring(Ecosystem ecosystem, String name) {
    return store.releasePinCarriers(ecosystem, name);
  }

  /**
   * <b>INTERNAL only, and GITLINK is the half that matters most.</b> EXTERNAL is somebody else's
   * package and nothing of ours releasing it makes anybody downstream; REACTOR is the repository's
   * own artifact and UNRESOLVED never became a version. A gitlink survives because {@code kindOf}
   * hardcodes it INTERNAL — and its "version" is the embedded commit, kept verbatim.
   */
  @Test
  void onlyTheInternalPinsOfTheReleasedTreeAreRecorded() {
    String repository = "qits-ledger-service" + run;
    String internalNpm = "@qits/ui-components" + run;
    String internalMaven = "eu.wohlben.qits:qits-eventstream" + run;
    String externalNpm = "@angular/core" + run;
    String externalMaven = "io.quarkus:quarkus-core" + run;
    String submodule = "qits-ledger-frontend" + run;
    String unresolved = "eu.wohlben.qits:${qits.thing}" + run;
    String own = "eu.wohlben.qits:qits-ledger-domain" + run;

    trees.holds(
        repository,
        ReleaseLedger.TAG_PREFIX + "2026.906.1",
        SHA,
        pin(Ecosystem.NPM, internalNpm, "1.2.3"),
        pin(Ecosystem.MAVEN, internalMaven, "2026.905.1"),
        pin(Ecosystem.NPM, externalNpm, "20.0.0"),
        pin(Ecosystem.MAVEN, externalMaven, "3.29.0"),
        pin(Ecosystem.GITLINK, submodule, SHA),
        pin(Ecosystem.MAVEN, unresolved, "1.0.0"),
        pin(Ecosystem.MAVEN, own, "9.9.9").withReactorOwn(true));

    assertTrue(ledger.record("qits", repository, "2026.906.1", SHA, APRIL));

    assertEquals(
        List.of("qits/" + repository + " refs/tags/2026.906.1"),
        trees.asked,
        "the tag is spelled in full, so no branch of that name can answer for it");

    assertEquals(1, declaring(Ecosystem.NPM, internalNpm).size());
    assertEquals(1, declaring(Ecosystem.MAVEN, internalMaven).size());
    assertEquals(
        1, declaring(Ecosystem.GITLINK, submodule).size(), "a submodule is INTERNAL by construction");
    assertTrue(declaring(Ecosystem.NPM, externalNpm).isEmpty());
    assertTrue(declaring(Ecosystem.MAVEN, externalMaven).isEmpty());
    assertTrue(declaring(Ecosystem.MAVEN, unresolved).isEmpty());
    assertTrue(declaring(Ecosystem.MAVEN, own).isEmpty(), "REACTOR is this repository's own");

    MaintenanceStore.ReleaseCarrier carrier = declaring(Ecosystem.GITLINK, submodule).get(0);
    assertEquals(repository, carrier.release().repository, "the catalog name, never another");
    assertEquals("2026.906.1", carrier.release().version);
    assertEquals(SHA, carrier.release().sha);
    assertEquals(APRIL, carrier.release().occurredAt);
    assertEquals(SHA, carrier.pin().version, "a gitlink's version is the embedded commit");
  }

  /**
   * <b>Recording twice converges.</b> A durable redelivery and a backfill re-run both aim at
   * {@code (repository, version)}; the pins are REWRITTEN rather than added to, so a re-read of a
   * tag whose parse has since improved replaces what the first reading said.
   */
  @Test
  void recordingTheSameReleaseAgainReplacesItsPinsRatherThanAddingToThem() {
    String repository = "qits-idempotent" + run;
    String kept = "@qits/kept" + run;
    String dropped = "@qits/dropped" + run;
    String revision = ReleaseLedger.TAG_PREFIX + "1.0.0";

    trees.holds(
        repository, revision, SHA, pin(Ecosystem.NPM, kept, "1.0.0"),
        pin(Ecosystem.NPM, dropped, "1.0.0"));
    ledger.record("qits", repository, "1.0.0", SHA, APRIL);

    // The second reading of the same tag sees one of them and not the other.
    trees.answers.clear();
    trees.holds(repository, revision, SHA, pin(Ecosystem.NPM, kept, "2.0.0"));
    ledger.record("qits", repository, "1.0.0", SHA, APRIL);

    assertEquals(1, store.releasesOf(repository).size(), "one row per released version");
    assertEquals(1, declaring(Ecosystem.NPM, kept).size(), "not two copies of the pin");
    assertEquals("2.0.0", declaring(Ecosystem.NPM, kept).get(0).pin().version);
    assertTrue(
        declaring(Ecosystem.NPM, dropped).isEmpty(),
        "the pins are rewritten, so what the second reading did not see is gone");
  }

  /**
   * A tag the git host does not hold is POISON — the same question has the same answer for ever —
   * so nothing is recorded and the frame is settled.
   */
  @Test
  void aTagTheGitHostDoesNotHoldRecordsNothingAndDoesNotThrow() {
    String repository = "qits-no-tag" + run;

    assertFalse(ledger.record("qits", repository, "1.0.0", SHA, APRIL));

    assertTrue(store.releasesOf(repository).isEmpty());
    assertFalse(store.releaseRecorded(repository, "1.0.0"));
  }

  /**
   * A git host that cannot be ASKED is retryable, and it is thrown on purpose: on the bus path the
   * claim rolls back and the release is offered again, which is the only thing that fills this row.
   */
  @Test
  void aGitHostThatWillNotAnswerIsLeftToThrow() {
    String repository = "qits-dark-host" + run;
    trees.unreachable(repository, ReleaseLedger.TAG_PREFIX + "1.0.0");

    assertThrows(
        IllegalStateException.class, () -> ledger.record("qits", repository, "1.0.0", SHA, APRIL));

    assertTrue(store.releasesOf(repository).isEmpty());
  }

  /** A release that declared nothing internal is still a release, and its row is still written. */
  @Test
  void aReleaseThatDeclaresNothingInternalStillGetsItsRow() {
    String repository = "qits-bare" + run;
    trees.holds(
        repository, ReleaseLedger.TAG_PREFIX + "1.0.0", SHA,
        pin(Ecosystem.NPM, "@angular/core" + run, "20.0.0"));

    assertTrue(ledger.record("qits", repository, "1.0.0", SHA, APRIL));

    assertEquals(1, store.releasesOf(repository).size());
    assertTrue(store.releaseRecorded(repository, "1.0.0"));
  }
}
