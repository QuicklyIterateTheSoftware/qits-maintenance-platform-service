package eu.wohlben.qits.maintenance.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>Reading qits-configuration's image pins.</b>
 *
 * <p>Every field here belongs to another service, so the whole subject of this class is what happens
 * when one is missing, null, or not the shape this side expected. A parser that turned any of them
 * into an exception would make the next change over there a sweep that stops — and a sweep that
 * stops leaves nodes PENDING for ever with nothing saying why.
 *
 * <p>It reads {@link ConfigPinsClient#parse} directly rather than through {@code read()}: the peer
 * client, the token and the transport are somebody else's tests, and every decision under
 * examination here is taken on one {@code JsonNode}. What the transport does with a refusal is
 * asserted where the sweep is, against the real {@code FakePeers} seam.
 */
class ConfigPinsClientTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The shipped answer, and the three things the sweep joins on. */
  @Test
  void aWholeAnswerReadsAsRowsWithTheMomentItWasComposed() {
    ConfigPinsClient.Result result =
        parse(
            """
            {"generatedAt":"2026-09-05T10:15:00Z",
             "pins":[{"image":"qits/workspace","version":"2026.905.4",
                      "application":"qits-workspaces","key":"env.QITS_WORKSPACE_IMAGE_VERSION"},
                     {"image":"qits/project-agent","version":"2026.904.9",
                      "application":"qits-projects","key":"env.QITS_AGENT_IMAGE_VERSION"}]}
            """);

    assertTrue(result.ok());
    assertEquals(Instant.parse("2026-09-05T10:15:00Z"), result.generatedAt());
    assertEquals(2, result.pins().size());
    ConfigPinsClient.Pin first = result.pins().getFirst();
    // UNQUALIFIED, which is what lets it be compared with mt_artifact.name without normalising a
    // registry host out of it.
    assertEquals("qits/workspace", first.image());
    assertEquals("2026.905.4", first.version());
    assertEquals("qits-workspaces", first.application());
    assertEquals("env.QITS_WORKSPACE_IMAGE_VERSION", first.key());
  }

  /**
   * <b>An answer with no rows is an ANSWER.</b> Nothing deploys the released image — a legitimate
   * reading that places no nodes — and it must not be confused with a read that failed, which places
   * none either but for the opposite reason.
   */
  @Test
  void anEmptyPinsArrayIsSuccessAndNotAFailure() {
    ConfigPinsClient.Result result = parse("{\"generatedAt\":\"2026-09-05T10:15:00Z\",\"pins\":[]}");

    assertTrue(result.ok(), "no pins is an answer");
    assertEquals(List.of(), result.pins());
  }

  /** A body that is not the shape at all — HTML from a proxy, an error document, a bare array. */
  @Test
  void garbageIsAnErrorRatherThanAnEmptyAnswer() {
    assertFalse(parse("[]").ok());
    assertFalse(parse("{\"pins\":\"soon\"}").ok());
    assertFalse(parse("{\"error\":\"nope\"}").ok());
    // Not JSON at all: PeerClient hands the client a null tree, which reads the same way.
    ConfigPinsClient.Result unparseable = ConfigPinsClient.parse(null);
    assertFalse(unparseable.ok());
    assertEquals(List.of(), unparseable.pins());
  }

  /**
   * A row missing any of the three fields a join needs is DROPPED and its siblings are kept. Half a
   * row is never evidence that an application took a version, and refusing the whole answer over one
   * of them would let one bad configuration entry hold up every train.
   */
  @Test
  void aRowThatCannotBeJoinedIsDroppedAndTheRestOfTheAnswerStands() {
    ConfigPinsClient.Result result =
        parse(
            """
            {"pins":[{"version":"1","application":"a","key":"k"},
                     {"image":"qits/workspace","application":"a","key":"k"},
                     {"image":"qits/workspace","version":"1","key":"k"},
                     {"image":"qits/workspace","version":null,"application":"a","key":"k"},
                     {"image":"qits/workspace","version":"2026.905.4","application":"qits-workspaces"}]}
            """);

    assertTrue(result.ok());
    assertEquals(1, result.pins().size(), "four of the five cannot be joined onto anything");
    // …and `key` is the one field that may be absent: it names the configuration entry, which
    // nothing joins on.
    assertNull(result.pins().getFirst().key());
    assertEquals("qits-workspaces", result.pins().getFirst().application());
  }

  /**
   * <b>A moment this side cannot read costs the landing its stamp and nothing else.</b> The sweep
   * falls back to its own clock; refusing the answer over a timestamp would leave a real observation
   * unrecorded because of a field no decision is taken on.
   */
  @Test
  void anUnreadableGeneratedAtIsNullAndTheAnswerStillReads() {
    ConfigPinsClient.Result result =
        parse(
            "{\"generatedAt\":\"yesterday\",\"pins\":[{\"image\":\"qits/workspace\","
                + "\"version\":\"1\",\"application\":\"a\",\"key\":\"k\"}]}");

    assertTrue(result.ok());
    assertNull(result.generatedAt());
    assertEquals(1, result.pins().size());
  }

  private static ConfigPinsClient.Result parse(String body) {
    return ConfigPinsClient.parse(tree(body));
  }

  private static JsonNode tree(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
