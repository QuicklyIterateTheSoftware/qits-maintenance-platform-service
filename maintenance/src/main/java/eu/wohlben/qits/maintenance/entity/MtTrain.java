package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One release's journey across the estate — the STATION every {@code SoftwareRelease} frame of that
 * release folds into.
 *
 * <p><b>One row per released {@code (repository, version)}, never per package.</b> A pipeline that
 * publishes a maven artifact, an npm package, a docker image and an api-docs bundle emits four
 * frames, and all four are the same release: the first to arrive creates this row and the other
 * three settle onto it. The unique index is what makes that true under redelivery as well.
 *
 * <p><b>A LOG, not a cache.</b> Unlike {@code mt_repository}, {@code mt_pin} and {@code mt_latest},
 * nothing here is re-derivable after the fact: the expected adopters are who pinned the released
 * coordinate AT THE MOMENT OF THE RELEASE, and a repository that dropped the dependency the next day
 * was still owed the adoption. No scan rewrites these rows.
 *
 * <p>Panache active-record with public fields, the platform's entity idiom.
 */
@Entity
@Table(name = "mt_train")
public class MtTrain extends PanacheEntityBase {

  @Id public UUID id;

  /**
   * The releasing repository, by CATALOG NAME. The frame spells it as qits-projects' row uuid; the
   * listener resolves it through {@code MaintenanceStore.repositoryName} before anything is written
   * here, because every read on this side — node derivation, supersession, the UI's link — joins a
   * name. An unknown spelling is kept verbatim rather than dropped.
   */
  @Column(nullable = false, length = 255)
  public String repository;

  /** The released version, as the release announced it. */
  @Column(nullable = false, length = 255)
  public String version;

  /** {@code TrainStatus}'s names: OPEN, COMPLETED or SUPERSEDED. There is no FAILED. */
  @Column(nullable = false, length = 32)
  public String status;

  /**
   * The frame's {@code occurredAt}, <b>never this service's clock</b> — it is the key supersession
   * is decided on. A catch-up that replays four releases in seconds would otherwise order them by
   * arrival rather than by publication, and the wrong one of the four would win.
   */
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  /**
   * When the journey ended, whichever way. Null while OPEN. A train with no expected adopters
   * carries {@link #createdAt} here: it arrived the instant it left.
   */
  @Column(name = "completed_at")
  public Instant completedAt;

  /**
   * Which train made this one irrelevant. Null unless the status is SUPERSEDED. A flat uuid with no
   * foreign key — it points at a peer record rather than at a part of this one.
   */
  @Column(name = "superseded_by")
  public UUID supersededBy;
}
