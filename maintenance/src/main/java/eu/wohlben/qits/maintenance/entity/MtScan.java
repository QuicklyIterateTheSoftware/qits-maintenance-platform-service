package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One scan, from the moment it was queued to the moment it ended.
 *
 * <p><b>A row rather than a log line, because the client follows it.</b> {@code POST /scans}
 * answers 202 with this id and the UI polls {@code GET /scans/{id}} until it is no longer running —
 * a scan of the whole catalog is one git-host read per repository and a registry lookup per
 * dependency, so a person who pressed the button needs to see that something is happening.
 *
 * <p><b>A scan FAILS only when it could do nothing at all.</b> One unreachable repository is that
 * repository's status; the scan still read the other seventy and is a success.
 */
@Entity
@Table(name = "mt_scan")
public class MtScan extends PanacheEntityBase {

  @Id public UUID id;

  /** {@code ScanScope}'s names: INTERNAL, EXTERNAL or ALL. */
  @Column(nullable = false, length = 32)
  public String scope;

  /** One repository's name, or null for the whole catalog. */
  @Column(length = 255)
  public String repository;

  /** {@code ScanTrigger}'s names: MANUAL or SCHEDULED. */
  @Column(name = "trigger", nullable = false, length = 32)
  public String trigger;

  /** REQUESTED, RUNNING, SUCCEEDED or FAILED. */
  @Column(nullable = false, length = 32)
  public String status;

  @Column(name = "started_at", nullable = false)
  public Instant startedAt;

  /** Null while it runs, and null forever for a scan whose process died. */
  @Column(name = "finished_at")
  public Instant finishedAt;

  @Column(columnDefinition = "text")
  public String message;
}
