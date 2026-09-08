package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One dependency a released tree declared, read at {@code refs/tags/<version>}.
 *
 * <p><b>INTERNAL pins only.</b> {@code adoption/ReleaseLedger} drops everything {@code
 * MaintenanceConfig.kindOf} does not call INTERNAL before a row is written: EXTERNAL is somebody
 * else's package and nothing on this platform releasing it makes anyone downstream, and REACTOR and
 * UNRESOLVED name no version anything could compare. A GITLINK is INTERNAL by construction, which
 * is why the half that matters most survives that filter without a special case.
 *
 * <p><b>{@link #version} is a version for the three registry ecosystems and a COMMIT SHA for a
 * gitlink</b> — the same asymmetry {@link MtPin#version} carries, and for the same reason: {@code
 * .gitmodules} names a submodule and the parent's tree holds its commit. A reader resolves that sha
 * through the submodule repository's own {@link MtRelease} rows to get a version it can order.
 *
 * <p><b>Flat, with no foreign key at {@link #releaseId}</b>, unlike {@link MtArtifactComponent}.
 * The rows are rewritten wholesale by a delete keyed on that column inside the same transaction
 * that replaces the release row, so the constraint would buy nothing the writer does not already
 * guarantee — and it leaves V3's "the only foreign keys in this schema" sentence standing.
 */
@Entity
@Table(name = "mt_release_pin")
public class MtReleasePin extends PanacheEntityBase {

  @Id public UUID id;

  /** The {@link MtRelease} whose tree declared it. */
  @Column(name = "release_id", nullable = false)
  public UUID releaseId;

  /** {@code Ecosystem}'s wire name — {@code gitlink} included, unlike the artifact graph. */
  @Column(nullable = false, length = 32)
  public String ecosystem;

  /** {@code mt_pin}'s spelling, which for a gitlink is the submodule's repository name. */
  @Column(nullable = false, length = 512)
  public String name;

  /** What the tree pinned it at: a version, or a commit sha for a gitlink. */
  @Column(length = 255)
  public String version;
}
