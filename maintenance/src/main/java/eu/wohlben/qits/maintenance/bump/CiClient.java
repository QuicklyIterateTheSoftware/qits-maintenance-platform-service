package eu.wohlben.qits.maintenance.bump;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import eu.wohlben.qits.maintenance.pending.Change;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The two calls this service makes to qits-ci: apply a bump, and read the run that applies it.
 *
 * <p><b>{@code eventId} is the bump's row id and that is the dedupe key.</b> qits-ci records at most
 * one run per (event id, repository, config path), so a dispatch whose ANSWER this service lost —
 * a timeout after qits-ci already accepted it — records no second run when it is retried. Without
 * it a retry would be a second branch commit for changes that were already applied.
 *
 * <p><b>503 is a retry, not a failure.</b> qits-ci answers it when the evaluation reached no
 * readable repository, which is a git host that is briefly away rather than a pipeline that
 * refused. The bump stays REQUESTED with its changes and the poller dispatches it again.
 *
 * <p><b>200 with no run id is NOT SUCCESS.</b> qits-ci records a run only if the repository the
 * payload names was READABLE in that same evaluation, so an empty {@code runIds} means one of two
 * things: the platform pipeline did not match — it is not there, or its {@code event:} does not say
 * {@code MaintenanceBump} — or the repository could not be read this time. Nothing is running
 * either way, so the bump is FAILED and the next scheduled scan asks again. Treating it as success
 * would report a branch that was never written.
 *
 * <p><b>{@code payload.repository} is the repository's public NAME</b>, which qits-ci resolves
 * against its candidate list — qits-projects' catalog, the same listing this service scans from. An
 * id would name nothing over there.
 */
@ApplicationScoped
public class CiClient {

  /** The event name the platform-level pipeline selects on. */
  public static final String EVENT_NAME = "MaintenanceBump";

  public static final String TRIGGER_PATH = "/ci/api/events/trigger";

  /** The file in the wrapper repository that declares the pipeline. qits-ci records it as a run's
   * {@code configPath}, and it is the same for every bump, so the bump detail carries it as a
   * constant rather than reading it back per run. */
  public static final String CONFIG_PATH = ".config/qits/ci-platform-event-maintenance-bump.yml";

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject PeerClient peers;

  /**
   * What qits-ci said about a trigger.
   *
   * @param outcome how to treat it
   * @param eventId the id the evaluation ran under
   * @param runIds the runs that now exist
   * @param message the sentence, for a row's message column
   */
  public record TriggerResult(Outcome outcome, String eventId, List<String> runIds, String message) {

    public enum Outcome {
      /** qits-ci accepted the trigger and named at least one run. */
      ACCEPTED,

      /** qits-ci could not evaluate it now. Retry with the same event id. */
      RETRY,

      /** No pipeline answered, or the call was refused. Nothing is running. */
      FAILED
    }
  }

  /**
   * Asks qits-ci to apply one group's changes.
   *
   * @param bumpId the {@code mt_bump} row id, which travels as the event's dedupe key
   */
  public TriggerResult trigger(
      String bumpId, String repository, String group, String branch, String baseRef, List<Change> changes) {
    ObjectNode payload = JSON.createObjectNode();
    payload.put("repository", repository);
    payload.put("group", group);
    payload.put("branch", branch);
    payload.put("baseRef", baseRef);
    payload.set("changes", JSON.valueToTree(changes));

    ObjectNode body = JSON.createObjectNode();
    body.put("name", EVENT_NAME);
    body.put("eventId", bumpId);
    body.set("payload", payload);

    PeerExchange exchange = peers.post(PeerTarget.CI, TRIGGER_PATH, body.toString());
    PeerAnswer answer = exchange.answer();

    if (answer.httpStatus() != null && answer.httpStatus() == 503) {
      return new TriggerResult(
          TriggerResult.Outcome.RETRY,
          bumpId,
          List.of(),
          "qits-ci could not evaluate the trigger yet; it will be sent again");
    }
    if (!answer.ok()) {
      return new TriggerResult(
          TriggerResult.Outcome.FAILED,
          bumpId,
          List.of(),
          "qits-ci refused the trigger: " + answer.failure());
    }
    JsonNode result = answer.json();
    List<String> runIds = runIds(result);
    if (runIds.isEmpty()) {
      return new TriggerResult(
          TriggerResult.Outcome.FAILED,
          eventId(result, bumpId),
          List.of(),
          "no run recorded for " + EVENT_NAME
              + " (repository unreadable or no platform pipeline)");
    }
    return new TriggerResult(TriggerResult.Outcome.ACCEPTED, eventId(result, bumpId), runIds, null);
  }

  /**
   * One run's state.
   *
   * @param status the CI status verbatim, or null when the run could not be read
   * @param error why it could not be read
   */
  public record RunState(String status, String error) {

    /** The four statuses nothing further happens after. */
    public boolean terminal() {
      return status != null
          && List.of("SUCCESS", "FAILED", "CANCELLED", "CONFIG_ERROR").contains(status);
    }

    /** Only one of the four terminal statuses means the step did its work. */
    public boolean passed() {
      return "SUCCESS".equals(status);
    }
  }

  /** Reads one run. */
  public RunState run(String runId) {
    PeerAnswer answer = peers.get(PeerTarget.CI, "/ci/api/runs/" + runId).answer();
    if (!answer.ok()) {
      return new RunState(null, "the run " + runId + " could not be read: " + answer.failure());
    }
    JsonNode body = answer.json();
    if (body == null || !body.hasNonNull("status")) {
      return new RunState(null, "the run " + runId + " answered no status");
    }
    return new RunState(body.get("status").asText(), null);
  }

  private static List<String> runIds(JsonNode result) {
    List<String> ids = new ArrayList<>();
    if (result == null || !result.hasNonNull("runIds") || !result.get("runIds").isArray()) {
      return ids;
    }
    for (JsonNode id : result.get("runIds")) {
      if (id.isTextual() && !id.asText().isBlank()) {
        ids.add(id.asText());
      }
    }
    return ids;
  }

  private static String eventId(JsonNode result, String fallback) {
    return Optional.ofNullable(result)
        .map(node -> node.get("eventId"))
        .filter(JsonNode::isTextual)
        .map(JsonNode::asText)
        .orElse(fallback);
  }
}
