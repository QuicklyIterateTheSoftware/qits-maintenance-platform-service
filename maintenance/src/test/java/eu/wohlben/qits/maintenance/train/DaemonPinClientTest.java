package eu.wohlben.qits.maintenance.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * <b>Reading qits-ci's daemon pin.</b> The same subject as {@code ConfigPinsClientTest} and the same
 * stance: the fields are another service's, and every shape they can arrive in has to be an answer
 * or an error, never an exception.
 *
 * <p><b>The wire names are the {@code daemon}-prefixed ones and this is where that is pinned.</b>
 * qits-ci's controller renames the three fields of its internal record on the way out — reading
 * {@code version} instead of {@code daemonVersion} would bind nulls, which would look exactly like a
 * ladder that has adopted nothing and would hold every daemon train open for ever with nothing in a
 * log.
 */
class DaemonPinClientTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void theWireFieldsAreTheDaemonPrefixedOnes() {
    DaemonPinClient.Result result =
        parse(
            """
            {"daemonName":"qits-ci-daemon","daemonVersion":"2026.905.4",
             "previousDaemonVersion":"2026.905.1","source":"adopted"}
            """);

    assertTrue(result.ok());
    assertEquals("qits-ci-daemon", result.daemon());
    assertEquals("2026.905.4", result.version());
    assertEquals("2026.905.1", result.previousVersion());
    assertEquals("adopted", result.source());
  }

  /**
   * <b>{@code configured} is an answer like any other.</b> A platform that pinned a daemon build by
   * hand is still handing that build out; refusing it would mean such a train never closed. What the
   * sweep does with the source is nothing — see {@code TrainSweep} — and this pins that the client
   * carries it through rather than filtering on it.
   */
  @Test
  void aConfiguredSourceReadsTheSameWayAnAdoptedOneDoes() {
    DaemonPinClient.Result result =
        parse(
            "{\"daemonName\":\"qits-ci-daemon\",\"daemonVersion\":\"2026.905.4\","
                + "\"previousDaemonVersion\":\"\",\"source\":\"configured\"}");

    assertTrue(result.ok());
    assertEquals("2026.905.4", result.version());
    assertEquals("configured", result.source());
    assertNull(result.previousVersion(), "an empty string is nothing, and reads as nothing");
  }

  /**
   * <b>{@code none} carries a blank version, and a blank version is an ANSWER.</b> qits-ci has
   * nothing proven and nothing configured; the read succeeded and there is simply no build to
   * compare, which lands nobody.
   */
  @Test
  void noneIsASuccessfulReadWithNoVersionInIt() {
    DaemonPinClient.Result result =
        parse(
            "{\"daemonName\":\"qits-ci-daemon\",\"daemonVersion\":\"\","
                + "\"previousDaemonVersion\":\"\",\"source\":\"none\"}");

    assertTrue(result.ok(), "the ladder answered; it just has nobody on it");
    assertNull(result.version());
    assertEquals("none", result.source());
  }

  /**
   * <b>No {@code daemonName} is an error rather than a lenient read.</b> Without it there is no
   * telling which daemon the version belongs to, and landing a train on an answer about a different
   * daemon would be worse than landing nothing at all.
   */
  @Test
  void anAnswerThatNamesNoDaemonIsRefused() {
    DaemonPinClient.Result result =
        parse("{\"daemonVersion\":\"2026.905.4\",\"source\":\"adopted\"}");

    assertFalse(result.ok());
    assertNull(result.version(), "nothing is carried out of an answer that cannot be attributed");
  }

  /** A body that is not an object at all — an error document, a bare array, or no JSON. */
  @Test
  void garbageIsAnError() {
    assertFalse(parse("[]").ok());
    assertFalse(parse("\"nope\"").ok());
    assertFalse(DaemonPinClient.parse(null).ok());
  }

  private static DaemonPinClient.Result parse(String body) {
    return DaemonPinClient.parse(tree(body));
  }

  private static JsonNode tree(String body) {
    try {
      return JSON.readTree(body);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
