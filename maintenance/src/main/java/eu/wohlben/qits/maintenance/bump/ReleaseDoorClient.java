package eu.wohlben.qits.maintenance.bump;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The release door: the one call this service makes to qits-workspaces, and the only thing besides
 * the qits-ci trigger that it asks anybody to do.
 *
 * <h2>What the door is</h2>
 *
 * <p>{@code POST /workspaces/api/branches/release?projectId=&repositoryName=} does not merge
 * anything. It CREATES A RELEASE REQUEST in qits-projects, which the quality gates settle off the
 * commit ledger and which executes when they pass. So the answer is a row to poll, not an outcome —
 * and this service deliberately does not poll it. {@code bus/ScmEventListener} already learns that a
 * maintenance branch was released, from the {@code SCMRelease} the door publishes when it lands, and
 * two mechanisms watching one fact would be two ways to disagree about it.
 *
 * <p><b>The ask is CONVERGENT, and that is what carried the rollout.</b> Through the transition every
 * repository also carried {@code .config/qits/ci-event-maintenance-release.yml} (or the same step
 * inline in {@code ci-post-receive.yml}), firing on the same push this bump caused; the door answers
 * the second ask with the request the first made ("created or converged"), so double-asking cost one
 * HTTP call and nothing else. Those per-repo triggers are gone as of 2026-09-03 and this is the only
 * caller. Convergence stays because it is also what makes a retry of this call free.
 *
 * <h2>{@code expectedSha} pins the ask to what was built</h2>
 *
 * <p>The sha sent is the branch head {@code BumpService} read AFTER the run — the same read that
 * decided the bump SUCCEEDED rather than NOTHING_TO_DO. Without it the door arms the request with
 * whatever the branch holds at the instant it is asked, which is a different commit whenever somebody
 * pushed onto the branch in between; with it, a head that moved is a refusal rather than a release of
 * something nobody reviewed.
 *
 * <h2>The four answers, and which one is retried</h2>
 *
 * <ul>
 *   <li><b>REQUESTED</b> — 2xx carrying a {@code requestId}. Stored on the bump row.
 *   <li><b>CONVERGED</b> — the door says this branch is already integrated. Not an error: it is the
 *       CI-side trigger having got there first, which during the rollout is the ordinary case. The
 *       409 {@code ALREADY_INTEGRATED} shape is what the old door answered and what the CI step still
 *       branches on; the request-shaped door converges at 200 instead. Both are read here, because a
 *       client that only understood the new one would report the old platform's ordinary answer as a
 *       failure.
 *   <li><b>REFUSED</b> — a 400 or a 404: a blank or defaulted branch, an unknown project/repository
 *       pair. The same bytes fail identically every time, so nothing retries it; the sentence goes on
 *       the bump's message where a person reads it.
 *   <li><b>RETRY</b> — a transport failure, a 5xx, or a 401/403. The first two are somebody's outage.
 *       The third is this service's idp client lacking {@code qits:admin}, which the door requires
 *       and which is a deployment grant rather than a code change — so it is retried, and it heals
 *       the moment the grant lands rather than needing the bump to be re-run.
 * </ul>
 */
@ApplicationScoped
public class ReleaseDoorClient {

  /** The door's path on qits-workspaces. The query half is built per call. */
  public static final String RELEASE_PATH = "/workspaces/api/branches/release";

  /**
   * What the column holds when the ask settled with no request id of its own: the door said the
   * branch is already integrated, or the branch was released or deleted before the ask could be
   * made. Either way nothing is owed.
   */
  public static final String CONVERGED = "converged";

  /** …and when the door refused in a way a retry cannot fix. The sentence is on {@code message}. */
  public static final String REFUSED = "refused";

  /** The door caps {@code summary} at 100 characters and answers 400 above it. */
  static final int SUMMARY_LIMIT = 100;

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject PeerClient peers;

  /**
   * What the door said.
   *
   * @param outcome how to treat it
   * @param requestId the release request, or null when there is none to hold on to
   * @param message the sentence for the bump's message column, or null when there is nothing to say
   */
  public record DoorResult(Outcome outcome, String requestId, String message) {

    public enum Outcome {
      /** A release request exists and this bump names it. */
      REQUESTED,

      /** There was nothing to ask for — the branch is already integrated. */
      CONVERGED,

      /** The door refused, and a retry would be refused identically. */
      REFUSED,

      /** Nobody answered, or the answer is one the next tick might not get. */
      RETRY
    }

    static DoorResult requested(String requestId, String state) {
      return new DoorResult(
          Outcome.REQUESTED,
          requestId,
          "the release request " + requestId + " is " + (state == null ? "open" : state));
    }
  }

  /**
   * Asks for one branch to be released.
   *
   * @param projectId the project half of the repository's PUBLIC identity, as {@code
   *     mt_repository.project} holds it. qits-projects' catalogue answers the project's row id there
   *     and the door resolves the segment by id first and then by slug, so the id addresses it — the
   *     same value {@code /git/<project>/<repo>} is read with
   * @param expectedSha the head this bump observed, which pins the request to exactly what was built
   */
  public DoorResult requestRelease(
      String projectId, String repository, String branch, String summary, String expectedSha) {
    ObjectNode body = JSON.createObjectNode();
    body.put("branch", branch);
    body.put("summary", cap(summary));
    if (expectedSha != null && !expectedSha.isBlank()) {
      body.put("expectedSha", expectedSha);
    }

    String path =
        RELEASE_PATH
            + "?projectId="
            + encode(projectId)
            + "&repositoryName="
            + encode(repository);
    PeerAnswer answer = peers.post(PeerTarget.WORKSPACES, path, body.toString()).answer();

    if (answer.ok()) {
      JsonNode result = answer.json();
      String requestId = text(result, "requestId");
      if (requestId == null) {
        // A 2xx with no id is the door having nothing to create. Recorded as converged rather than
        // retried: asking again would get the same empty answer.
        return new DoorResult(
            DoorResult.Outcome.CONVERGED,
            CONVERGED,
            "the release door answered without a request id: " + brief(answer.body()));
      }
      return DoorResult.requested(requestId, text(result, "state"));
    }

    if (alreadyIntegrated(answer)) {
      return new DoorResult(
          DoorResult.Outcome.CONVERGED, CONVERGED, branch + " is already released — nothing to ask for");
    }

    Integer status = answer.httpStatus();
    if (status != null && (status == 400 || status == 404)) {
      return new DoorResult(
          DoorResult.Outcome.REFUSED,
          REFUSED,
          "the release door refused " + branch + ": HTTP " + status + " " + brief(answer.body()));
    }
    if (status != null && (status == 401 || status == 403)) {
      return new DoorResult(
          DoorResult.Outcome.RETRY,
          null,
          "the release door would not admit this service (HTTP "
              + status
              + "); the door requires qits:admin on this service's idp client — the branch is"
              + " pushed and the next sweep asks again");
    }
    return new DoorResult(
        DoorResult.Outcome.RETRY,
        null,
        "the release door could not be reached ("
            + answer.failure()
            + "); the branch is pushed and the next sweep asks again");
  }

  /**
   * The commit subject a bump's own commits carry, reused as the release summary.
   *
   * <p><b>{@code bump(<group>): <n> dependencies}</b> — the literal shape {@code
   * .config/qits/ci-platform-event-maintenance-bump.yml} prints, word for word, the plural never
   * singularised. Reusing it means the release request, the branch and the commits on it all read
   * the same in a listing.
   *
   * <p><b>{@code n} is what was ASKED FOR, and it cannot be what a commit says.</b> The step counts
   * what it APPLIED, and a bump is up to two commits — the maven step and the node/docker step each
   * clone, commit and push — so there is no single number the subject could match. The count this
   * service froze onto the row is the one it can stand behind.
   */
  public static String summary(String group, int changes) {
    return cap("bump(" + group + "): " + changes + " dependencies");
  }

  /**
   * Whether the answer is the "already integrated" shape, in either of the two spellings a platform
   * mid-rollout can be running.
   *
   * <p>The structured one is qits-workspaces' own error envelope, {@code {"message":…,"reason":…}},
   * where {@code reason} is {@code ALREADY_INTEGRATED}. The loose one is any 4xx whose message says
   * the branch is already released — read because the door's refusal shapes have moved once already
   * and a convergence reported as a failure is the more expensive mistake of the two.
   */
  private static boolean alreadyIntegrated(PeerAnswer answer) {
    Integer status = answer.httpStatus();
    if (status == null || status < 400 || status >= 500) {
      return false;
    }
    JsonNode body = answer.json();
    if (body != null && body.hasNonNull("reason")) {
      return "ALREADY_INTEGRATED".equalsIgnoreCase(body.get("reason").asText());
    }
    String text = answer.body();
    return text != null && text.toLowerCase(Locale.ROOT).contains("already");
  }

  /** A summary the door will accept: it caps at 100 and answers 400 above it. */
  private static String cap(String summary) {
    if (summary == null) {
      return "";
    }
    return summary.length() <= SUMMARY_LIMIT ? summary : summary.substring(0, SUMMARY_LIMIT);
  }

  /** Somebody else's body on this service's message column: bounded, and never a wall of html. */
  private static String brief(String body) {
    if (body == null || body.isBlank()) {
      return "";
    }
    String flat = body.replace('\n', ' ').trim();
    return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
  }

  private static String text(JsonNode body, String field) {
    if (body == null || !body.hasNonNull(field) || !body.get(field).isTextual()) {
      return null;
    }
    String value = body.get(field).asText();
    return value.isBlank() ? null : value;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }
}
