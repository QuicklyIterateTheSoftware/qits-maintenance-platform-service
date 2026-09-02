package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One repository of the catalog, as the last scan found it.
 *
 * <p>Panache active-record with public fields, the platform's entity idiom.
 *
 * <p><b>The name is the key.</b> Every read this service makes is name-addressed — the catalog
 * answers names, the git host is asked by name, the CI payload carries a name — so a surrogate id
 * would only be a second identity to keep in step.
 */
@Entity
@Table(name = "mt_repository")
public class MtRepository extends PanacheEntityBase {

  @Id
  @Column(nullable = false, length = 255)
  public String name;

  /** The project the git host serves it under — half of the clone coordinate. */
  @Column(length = 255)
  public String project;

  /**
   * The catalog row's own id, as qits-projects answers it. <b>Not an identity here and never an
   * address</b> — the name above is both — but the one column that can turn ANOTHER context's
   * spelling of this repository back into a name: qits-ci's {@code SoftwareRelease} names the
   * repository by this id, so {@code mt_artifact.repository} would otherwise hold a uuid where the
   * whole read side joins on a name. Null for a row the catalog listed without one, and for every
   * row not yet re-scanned since V5.
   */
  @Column(name = "catalog_id", length = 64)
  public String catalogId;

  /** The branch a scan reads and a bump branches from — the payload's {@code baseRef}. */
  @Column(name = "main_branch", length = 255)
  public String mainBranch;

  /** When the last scan finished, whatever it found. Null means never scanned. */
  @Column(name = "last_scan_at")
  public Instant lastScanAt;

  /**
   * The commit every pin below was read at. ONE per scan: a repository's manifests are read at one
   * revision, so the inventory is a snapshot of a tree rather than a mixture of moments.
   */
  @Column(name = "head_sha", length = 64)
  public String headSha;

  /** {@code RepositoryStatus}'s names: OK, ABSENT, UNREACHABLE or CONFIG_ERROR. */
  @Column(nullable = false, length = 32)
  public String status;

  /** Why the status is not OK, for the UI to show. Null when it is. */
  @Column(columnDefinition = "text")
  public String message;
}
