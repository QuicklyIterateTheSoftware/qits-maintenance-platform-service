package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One group's maintenance branch, as this service last saw it.
 *
 * <p>The branch IS the merge request — there is no workspace and no review object — so this row is
 * the whole record of its life.
 *
 * <p><b>{@link #headSha} is read twice per bump and that is its purpose.</b> Before the trigger and
 * again when the CI run ends: a head that did not move means the step found nothing to write, which
 * is NOTHING_TO_DO rather than success. Nothing else on the platform could tell the two apart — a
 * green run is green either way.
 */
@Entity
@Table(name = "mt_branch")
public class MtBranch extends PanacheEntityBase {

  @Id public UUID id;

  @Column(nullable = false, length = 255)
  public String repository;

  @Column(name = "group_name", nullable = false, length = 255)
  public String groupName;

  /** The ref name without {@code refs/heads/}. */
  @Column(nullable = false, length = 512)
  public String branch;

  /** {@code BranchState}'s names: NONE, PUSHED, STALE, RELEASED or FAILED. */
  @Column(nullable = false, length = 32)
  public String state;

  /** The head as last read from the git host, or null when the branch does not exist. */
  @Column(name = "head_sha", length = 64)
  public String headSha;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
