package eu.wohlben.qits.maintenance.bus;

import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.frame;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.softwareReleasePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The listener's decision, in isolation from the bus and the database.
 *
 * <p>{@link RecordingStore} stands in for the write seam and keeps its own {@code (ecosystem, name)}
 * map, so the forward-only rule is exercised for real rather than mocked away — the stand-in applies
 * the same {@link VersionOrder} comparison {@code MaintenanceStore.recordLatestIfNewer} does, and
 * {@code MaintenanceStoreTest} is where that method is proved against a real PostgreSQL. This test
 * is about which frames reach it and with what.
 *
 * <p>The payloads come from {@link ForeignEventContractTest}'s transcription through the real
 * {@link eu.wohlben.qits.eventstream.control.CanonicalJson}, so the bytes here are the bytes qits-ci
 * publishes rather than a JSON literal this file guessed.
 */
class SoftwareReleaseListenerTest {

  /**
   * A {@link MaintenanceStore} that writes to a map instead of a database, applying the same
   * forward-only rule.
   */
  private static final class RecordingStore extends MaintenanceStore {

    final Map<String, String> latest = new LinkedHashMap<>();
    final Map<String, String> sourceUrls = new LinkedHashMap<>();
    final List<String> writes = new ArrayList<>();
    RuntimeException failWith;

    @Override
    public boolean recordLatestIfNewer(
        Ecosystem ecosystem, String name, String version, String sourceUrl, Instant now) {
      if (failWith != null) {
        throw failWith;
      }
      String key = ecosystem.wireName() + ":" + name;
      String current = latest.get(key);
      if (current != null && !VersionOrder.newer(ecosystem, current, version)) {
        return false;
      }
      latest.put(key, version);
      sourceUrls.put(key, sourceUrl);
      writes.add(key + "=" + version);
      return true;
    }
  }

  private SoftwareReleaseListener listener;
  private RecordingStore store;

  @BeforeEach
  void setUp() {
    store = new RecordingStore();
    listener = new SoftwareReleaseListener();
    listener.store = store;
  }

  private void release(String packageType, String packageName, String version) {
    listener.onFrame(
        frame("SoftwareRelease", softwareReleasePayload(packageType, packageName, version)));
  }

  @Test
  void itSubscribesToTheOneSignatureUnderItsOwnStorageKey() {
    assertEquals(Set.of("SoftwareRelease"), listener.signatures());
    assertEquals("maintenance-internal-latest", listener.consumerId());
  }

  /**
   * The three internal names, each spelled the way its own manifest parser records a pin — which is
   * the whole reason no translation table exists in the listener. A name that did not join would
   * write a row nothing reads and say nothing about it.
   */
  @Test
  void theThreeInternalNamesLandUnderTheSpellingMtPinUses() {
    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1");
    release("npm", "@qits/ui-components", "3.2.0");
    release("docker", "qits/qits-ci", "2026.901.2");

    assertEquals("2026.901.1", store.latest.get("maven:eu.wohlben.qits:qits-eventstream"));
    assertEquals("3.2.0", store.latest.get("npm:@qits/ui-components"));
    assertEquals("2026.901.2", store.latest.get("docker:qits/qits-ci"));
  }

  @Test
  void aReleaseMovesTheLatestForwardAndRecordsTheFrameAsItsSource() {
    EventFrame published =
        frame(
            "SoftwareRelease",
            softwareReleasePayload("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1"));
    listener.onFrame(published);

    assertEquals("2026.901.1", store.latest.get("maven:eu.wohlben.qits:qits-eventstream"));
    assertEquals(
        "event:" + published.id(),
        store.sourceUrls.get("maven:eu.wohlben.qits:qits-eventstream"),
        "a row a registry never answered has to say so, so a surprising value can be traced");
  }

  /**
   * The rule the whole listener exists to get right: an announcement is evidence that THIS version
   * exists, never that a higher one does not.
   */
  @Test
  void aStaleFrameNeverRewindsAVersionAlreadyRecorded() {
    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.5");
    release("maven", "eu.wohlben.qits:qits-eventstream", "2026.825.74539");

    assertEquals("2026.901.5", store.latest.get("maven:eu.wohlben.qits:qits-eventstream"));
    assertEquals(1, store.writes.size(), "the caught-up frame must write nothing at all");
  }

  /** The ordinary redelivery: the same release offered twice is a read and a return. */
  @Test
  void anEqualVersionSettlesQuietlyAndWritesNothing() {
    release("npm", "@qits/ui-components", "3.2.0");
    release("npm", "@qits/ui-components", "3.2.0");

    assertEquals(1, store.writes.size(), "a redelivery of one release must not rewrite the row");
    assertEquals("3.2.0", store.latest.get("npm:@qits/ui-components"));
  }

  /** npm ranks prereleases by rules maven does not share, and the guard is the ecosystem's own. */
  @Test
  void theForwardGuardIsTheEcosystemsOwnOrder() {
    release("npm", "@qits/angular", "1.0.0");
    release("npm", "@qits/angular", "1.0.0-rc.1");

    assertEquals(
        "1.0.0", store.latest.get("npm:@qits/angular"), "semver puts a prerelease below its release");
  }

  /**
   * {@code daemon} and {@code docs} are real releases of things no manifest this service parses ever
   * pins, and a type qits-ci adds later reads the same way. All of them settle.
   */
  @Test
  void aPackageTypeThisInventoryDoesNotHoldSettlesWithoutAWrite() {
    release("daemon", "qits-ci-daemon", "2026.901.1");
    release("docs", "@apidocs/qits-ci", "2026.901.1");
    release("cargo", "qits-something", "1.0.0");

    assertTrue(store.writes.isEmpty(), "nothing here is a pin any manifest holds");
  }

  @Test
  void aReleaseNamingNoVersionIsPoisonAndIsSettled() {
    release("maven", "eu.wohlben.qits:qits-eventstream", "");

    assertTrue(store.writes.isEmpty());
  }

  @Test
  void anUnreadablePayloadIsPoisonAndIsSettled() {
    listener.onFrame(frame("SoftwareRelease", "not json at all"));

    assertTrue(store.writes.isEmpty());
  }

  /**
   * The other half of the failure rule: a store that could not answer is a condition rather than a
   * verdict, so it is left to throw — the claim rolls back and the next catch-up sweep offers the
   * release again.
   */
  @Test
  void aStoreThatWillNotAnswerIsLeftToThrowSoTheReleaseStaysOwed() {
    store.failWith = new IllegalStateException("the database is not there");

    assertThrows(
        IllegalStateException.class,
        () -> release("maven", "eu.wohlben.qits:qits-eventstream", "2026.901.1"));
  }
}
