package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One group of one repository: a name, which is also a branch, and the globs that claim pins for
 * it.
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

  /** The globs as a JSON array of strings — read and written whole, never queried by. */
  @Column(nullable = false, columnDefinition = "text")
  public String patterns;

  /** {@code GroupSource}'s names: CONFIG or DEFAULT. */
  @Column(nullable = false, length = 32)
  public String source;
}
