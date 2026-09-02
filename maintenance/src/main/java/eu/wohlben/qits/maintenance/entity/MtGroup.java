package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One group of one repository: a name, which is also a branch, and what claims pins for it.
 *
 * <p><b>A group claims by KIND or by GLOB, never by both.</b> {@link #kind} set means the group
 * takes every pin of that {@code PinKind} and {@link #patterns} is an empty array nothing reads;
 * {@link #kind} null means the patterns decide. The two built-in groups a repository with no
 * configuration gets — {@code dependencies} for INTERNAL, {@code external} for EXTERNAL — are the
 * kind kind; a repository's own {@code .config/qits/maintenance.yml} declares the glob kind.
 *
 * <p><b>{@link #ordinal} carries the declaration order and is load-bearing.</b> A pin matching two
 * groups belongs to the FIRST one declared, so without this column "first" would be whatever order
 * the database happened to return rows in — a grouping that changes between two reads of the same
 * configuration.
 */
@Entity
@Table(name = "mt_group")
public class MtGroup extends PanacheEntityBase {

  @Id public UUID id;

  @Column(nullable = false, length = 255)
  public String repository;

  /** The group name, which is also the branch suffix: {@code maintenance/<name>}. */
  @Column(nullable = false, length = 255)
  public String name;

  /** Declaration order, from 0. First match wins, and this is what "first" means. */
  @Column(nullable = false)
  public int ordinal;

  /**
   * The globs as a JSON array of strings — read and written whole, never queried by. A kind group
   * stores {@code []} here and nothing reads it.
   */
  @Column(nullable = false, columnDefinition = "text")
  public String patterns;

  /**
   * {@code PinKind}'s names — INTERNAL or EXTERNAL — when this group claims by kind, null when it
   * claims by glob. Nullable because the glob mechanism is the older of the two and stays.
   */
  @Column(length = 32)
  public String kind;

  /** {@code GroupSource}'s names: CONFIG or DEFAULT. */
  @Column(nullable = false, length = 32)
  public String source;
}
