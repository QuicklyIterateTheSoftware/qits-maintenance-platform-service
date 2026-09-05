package eu.wohlben.qits.maintenance.train;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * <b>WHICH DAEMON BUILD qits-ci HANDS OUT</b> — the top rung of its adoption ladder, read as the end
 * of a DAEMON repository's release train.
 *
 * <p>A daemon is pinned by nobody: no manifest names it, because it is not a dependency but a binary
 * a service distributes. What decides whether a daemon release was adopted is the service's own
 * record of which build it hands out, and qits-ci answers that at {@code GET /ci/api/daemon}.
 *
 * <p><b>{@code source} is read and never gated on.</b> {@code adopted} is the top PROVEN rung of the
 * ladder, {@code configured} is the deployment's own pin answering because nothing was proven, and
 * {@code none} is neither — but all three are qits-ci saying "this is the build I hand out today",
 * and a node lands on the VERSION rather than on how it got there. Refusing a {@code configured}
 * answer would mean a platform that pinned a daemon by hand never closed a train, which is the
 * opposite of the truth.
 *
 * <p><b>The wire field names are {@code daemon}-prefixed and the answer names the daemon itself.</b>
 * qits-ci serves {@code {daemonName, daemonVersion, previousDaemonVersion, source}} — the internal
 * record behind it is spelled {@code (version, previousVersion, source)}, which is what a reader of
 * that service's control class sees, and the CONTROLLER renames all three. This client reads the
 * wire. {@code daemonName} being on the answer is what lets the sweep check that this ladder is the
 * one the train's repository climbs, rather than hardcoding which daemon lives behind {@code
 * PeerTarget.CI}.
 *
 * <p><b>A blank version is an ANSWER, not an absence</b> — it is what {@code source=none} carries —
 * and it simply never compares at or above a train's version, so it lands nobody.
 *
 * <p>Tree-parsed and never thrown out of, for the reasons in {@link ConfigPinsClient}.
 */
@ApplicationScoped
public class DaemonPinClient {

  /** qits-ci's own API. The CI target already exists for the bump's trigger. */
  public static final String PATH = "/ci/api/daemon";

  @Inject PeerClient peers;

  /**
   * What qits-ci hands out, or why nobody knows.
   *
   * @param daemon the daemon this answer is about, which a train's repository is checked against
   * @param version the build handed out now, possibly blank
   * @param previousVersion the rung below, blank when there is none. Read and not acted on — it is
   *     here so a log line can say what a ladder looked like
   * @param source {@code adopted}, {@code configured} or {@code none}
   * @param error the sentence, or null when the read succeeded
   */
  public record Result(
      String daemon, String version, String previousVersion, String source, String error) {

    public boolean ok() {
      return error == null;
    }
  }

  /** The one call: what build qits-ci distributes today. */
  public Result read() {
    PeerAnswer answer = peers.get(PeerTarget.CI, PATH).answer();
    if (!answer.ok()) {
      return new Result(
          null, null, null, null, "the daemon pin could not be read: " + answer.failure());
    }
    return parse(answer.json());
  }

  /** The answer as one row. Package-private for the same reason {@link ConfigPinsClient#parse} is. */
  static Result parse(JsonNode root) {
    if (root == null || !root.isObject()) {
      return new Result(null, null, null, null, "the daemon pin answered a body that is not an object");
    }
    String daemon = text(root, "daemonName");
    if (daemon == null) {
      // Without it there is no telling WHICH daemon the version belongs to, and landing a train on
      // an answer about another daemon would be worse than landing nothing.
      return new Result(null, null, null, null, "the daemon pin answered no daemonName");
    }
    return new Result(
        daemon,
        text(root, "daemonVersion"),
        text(root, "previousDaemonVersion"),
        text(root, "source"),
        null);
  }

  private static String text(JsonNode row, String field) {
    JsonNode value = row.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      return null;
    }
    return value.asText().trim();
  }
}
