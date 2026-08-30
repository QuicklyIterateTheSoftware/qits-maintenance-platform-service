package eu.wohlben.qits.maintenance.stories.support;

import static io.restassured.RestAssured.given;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * How a story waits, in a service whose every write answers 202.
 *
 * <p><b>Every poll is a fresh HTTP request, and that is load-bearing.</b> {@code POST /scans} and
 * {@code POST …/bumps} hand the work to one background thread and answer with an id; the only way to
 * know what became of it is to read the row back. Polling the ROUTE — rather than sleeping, or
 * reading the database directly — is also what makes the wait part of the story: {@code GET
 * /maintenance/api/scans/{id} -> 200} is an edge, and it is the edge a client's own progress spinner
 * would draw.
 *
 * <p><b>A wait is always bounded and always fails loudly.</b> A story that hung would be
 * indistinguishable from a story that is slow, and a build that hangs tells nobody anything.
 *
 * <p><b>The polls are all one edge.</b> The framework dedupes on the {@code (kind, from, to, label)}
 * quadruple, so twenty reads of one scan draw one arrow — the diagram says who reached what and got
 * what, and the steps say why.
 */
public final class StoryWaits {

  /** Long enough for a whole-catalog scan on a loaded machine; short enough to fail a build. */
  private static final Duration LIMIT = Duration.ofSeconds(90);

  /** Every status a scan or a bump stops at. */
  private static final List<String> TERMINAL =
      List.of("SUCCEEDED", "FAILED", "NOTHING_TO_DO");

  private StoryWaits() {}

  /** Polls one scan until it stops, and answers with the status it stopped at. */
  public static String scan(String id) {
    return terminal(StoryTarget.SCANS + "/" + id, "scan " + id);
  }

  /** Polls one bump until it stops, and answers with the status it stopped at. */
  public static String bump(String id) {
    return terminal(StoryTarget.BUMPS + "/" + id, "bump " + id);
  }

  /**
   * Polls one bump until its status is exactly {@code wanted}.
   *
   * <p>The state a bump story needs mid-flight is RUNNING, which is not terminal: the trigger has
   * been accepted and qits-ci has named a run, and that is the window in which a second request for
   * the same group is refused.
   */
  public static void bumpReaches(String id, String wanted) {
    until(
        () -> wanted.equals(status(StoryTarget.BUMPS + "/" + id)),
        "bump " + id + " never reached " + wanted);
  }

  /** Polls until {@code condition} holds, or fails naming what was being waited for. */
  public static void until(BooleanSupplier condition, String what) {
    Instant deadline = Instant.now().plus(LIMIT);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      pause();
    }
    throw new AssertionError(what + " within " + LIMIT);
  }

  private static String terminal(String path, String what) {
    Instant deadline = Instant.now().plus(LIMIT);
    while (Instant.now().isBefore(deadline)) {
      String status = status(path);
      if (TERMINAL.contains(status)) {
        return status;
      }
      pause();
    }
    throw new AssertionError(what + " never finished within " + LIMIT);
  }

  private static String status(String path) {
    return StoryIdentities.operator(given())
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .path("status");
  }

  private static void pause() {
    try {
      Thread.sleep(100);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }
}
