package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One request to qits-ci to apply one group's pending changes, and what came of it.
 *
 * <p><b>The id is also the CI event's dedupe key.</b> It goes out as {@code eventId}, so a dispatch
 * that reached qits-ci and whose answer this service lost records no second run when it is retried.
 *
 * <p><b>{@link #changes} is what was SENT, stored rather than recomputed.</b> By the time anyone
 * reads this row the pins have moved and the latest versions have moved again; "what did we ask
 * for" is the only question a surprising branch can be investigated with.
 */
@Entity
@Table(name = "mt_bump")
public class MtBump extends PanacheEntityBase {

  @Id public UUID id;

  @Column(nullable = false, length = 255)
  public String repository;

  @Column(name = "group_name", nullable = false, length = 255)
  public String groupName;

  /** The ref this bump writes, without {@code refs/heads/}. Stored rather than derived from the
   * group: it is what the payload carried, and a row must stay readable after the naming rule
   * changes. */
  @Column(nullable = false, length = 512)
  public String branch;

  /** Which environment's CI ran it. Platform tier calling a per-environment service, recorded so a
   * second environment is a config entry rather than a schema change. */
  @Column(nullable = false, length = 64)
  public String environment;

  /** {@code BumpTrigger}'s names: SCHEDULED or MANUAL. */
  @Column(name = "trigger", nullable = false, length = 32)
  public String trigger;

  /** The {@code eventId} the trigger carried. */
  @Column(name = "ci_event_id", length = 255)
  public String ciEventId;

  /** The run ids qits-ci answered with, comma-separated. Plural: a trigger can match more than one
   * pipeline, and the poller follows every id it was given. */
  @Column(name = "ci_run_id", columnDefinition = "text")
  public String ciRunId;

  /** The last CI run status read, verbatim, so a RUNNING bump can say what CI is doing. */
  @Column(name = "ci_run_status", length = 32)
  public String ciRunStatus;

  /** {@code BumpStatus}'s names. */
  @Column(nullable = false, length = 32)
  public String status;

  /** The changes as the JSON array the payload carried. */
  @Column(columnDefinition = "text")
  public String changes;

  @Column(name = "started_at", nullable = false)
  public Instant startedAt;

  /** Null while the bump is REQUESTED or RUNNING, and null forever for one whose process died. */
  @Column(name = "finished_at")
  public Instant finishedAt;

  @Column(columnDefinition = "text")
  public String message;

  /**
   * What came of asking qits-workspaces' release door for the branch this bump pushed.
   *
   * <p><b>Three shapes, and NULL is the one that means work is owed.</b> A release request id is the
   * row to poll in qits-projects; {@code converged} is "there was nothing to ask for" — the door said
   * already-integrated, or the branch was released or deleted first; {@code refused} is a refusal a
   * retry cannot fix, with the reason on {@link #message}. The sweep re-attempts the door for exactly
   * the SUCCEEDED rows where this is null and the branch is still PUSHED, so every ending writes
   * something here and stops being read.
   *
   * <p>Null for ever on a bump that is not SUCCEEDED: there is no branch to release.
   */
  @Column(name = "release_request_id", length = 255)
  public String releaseRequestId;
}
