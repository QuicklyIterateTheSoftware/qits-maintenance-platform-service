package eu.wohlben.qits.maintenance.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * <b>Reading one row of somebody else's listing.</b>
 *
 * <p>Every field here belongs to qits-projects, and the whole subject of this class is what happens
 * when a field is missing, null, or a word this service has never heard of. A parser that treated
 * any of the three as an error would turn the next change over there into a scan that stops.
 *
 * <p>It reads {@code entry} directly rather than through {@link CatalogReader#read()}: the peer
 * client, the token and the transport are somebody else's tests, and the decision under examination
 * is taken on one {@code JsonNode}.
 */
class CatalogReaderTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The archetype the wrapper repository itself answers — see {@link RepositoryArchetype#PROJECT}. */
  @Test
  void anArchetypeIsReadVerbatimFromTheRow() {
    CatalogEntry entry =
        parse(
            "{\"id\":\"r1\",\"projectId\":\"qits\",\"name\":\"qits-ci-service\","
                + "\"mainBranch\":\"main\",\"archetype\":\"SERVICE\"}");
    assertEquals("SERVICE", entry.archetype());
    assertEquals("r1", entry.catalogId());
    assertEquals("main", entry.mainBranch());
    assertEquals(
        Optional.of(RepositoryArchetype.SERVICE), RepositoryArchetype.of(entry.archetype()));
  }

  /**
   * <b>Absent and null are the same fact and neither is a skip.</b> The key is nullable over there
   * and an older answer carries none at all; a repository nobody classified still pins things, and
   * dropping it here would take it out of the inventory over a label.
   */
  @Test
  void aRowWithNoArchetypeIsStillARowAndIsCarriedWithANullOne() {
    CatalogEntry absent =
        parse("{\"id\":\"r1\",\"projectId\":\"qits\",\"name\":\"qits-ci-service\"}");
    assertNull(absent.archetype(), "an absent key is null, not a failure");
    assertEquals("qits-ci-service", absent.name(), "and the row is kept");

    CatalogEntry explicitNull =
        parse(
            "{\"id\":\"r1\",\"projectId\":\"qits\",\"name\":\"qits-ci-service\","
                + "\"archetype\":null}");
    assertNull(explicitNull.archetype());
    assertEquals("qits-ci-service", explicitNull.name());

    assertTrue(RepositoryArchetype.of(null).isEmpty());
  }

  /**
   * <b>AND AN UNKNOWN SPELLING IS CARRIED TOO.</b> The vocabulary is qits-projects' and it grows
   * without asking this service: a value added over there must cost a repository whatever a reader
   * of the archetype would have done with it — decided on {@code RepositoryArchetype.of} answering
   * empty — and never its inventory row. So the string survives the read intact.
   */
  @Test
  void anArchetypeThisServiceHasNeverHeardOfIsCarriedRatherThanDropped() {
    CatalogEntry entry =
        parse(
            "{\"id\":\"r1\",\"projectId\":\"qits\",\"name\":\"qits-ci-service\","
                + "\"archetype\":\"QUANTUM_MESH\"}");
    assertEquals("QUANTUM_MESH", entry.archetype(), "stored what arrived, verbatim");
    assertEquals("qits-ci-service", entry.name(), "and the row is addressable as ever");
    assertTrue(
        RepositoryArchetype.of(entry.archetype()).isEmpty(),
        "the parse is where the word is judged, and it judges by answering nothing");
  }

  /**
   * The three foreign values that name something other than a deployable. {@code PROJECT} is the
   * one this service acts on — the downstream closure excludes the wrapper by it — and all three
   * are read like any other word.
   */
  @Test
  void theArchetypesThatNameNoDeployableAreReadLikeAnyOther() {
    for (String spelling : new String[] {"PROJECT", "FORK", "SERVICE_TEMPLATE"}) {
      CatalogEntry entry =
          parse(
              "{\"id\":\"r1\",\"projectId\":\"qits\",\"name\":\"qits-qits\",\"archetype\":\""
                  + spelling
                  + "\"}");
      assertEquals(spelling, entry.archetype());
      assertEquals(
          spelling,
          RepositoryArchetype.of(entry.archetype()).orElseThrow().wireName(),
          "this service knows the word; whether it can be placed is not decided here");
    }
  }

  /** A row with no addressable name is still skipped, archetype or no archetype. */
  @Test
  void aRowWithNoNameIsSkippedEvenWhenItCarriesAnArchetype() {
    assertTrue(
        CatalogReader.entry(
                node("{\"id\":\"r1\",\"projectId\":\"qits\",\"name\":null,\"archetype\":\"SERVICE\"}"))
            .isEmpty());
  }

  private static CatalogEntry parse(String json) {
    return CatalogReader.entry(node(json)).orElseThrow();
  }

  private static JsonNode node(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
