package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The newest published version of one dependency, as one lookup found it.
 *
 * <p>One row per (ecosystem, name) — the pair {@code mt_pin} joins on. It is a cache of a
 * registry's answer, replaced by every lookup that reaches it.
 *
 * <p><b>A failed lookup is written too.</b> {@link #latest} null with {@link #error} set is what
 * lets the UI say "we could not find out" rather than showing nothing pending, which reads exactly
 * like "you are up to date".
 */
@Entity
@Table(name = "mt_latest")
public class MtLatest extends PanacheEntityBase {

  @Id public UUID id;

  /** {@code Ecosystem}'s wire name. */
  @Column(nullable = false, length = 32)
  public String ecosystem;

  @Column(nullable = false, length = 512)
  public String name;

  /** The newest version by that ecosystem's order, or null when the lookup failed. */
  @Column(length = 255)
  public String latest;

  @Column(name = "checked_at", nullable = false)
  public Instant checkedAt;

  /** The url that was read, so a surprising answer can be reproduced by hand. */
  @Column(name = "source_url", columnDefinition = "text")
  public String sourceUrl;

  /** Why there is no latest. Null when the lookup succeeded. */
  @Column(columnDefinition = "text")
  public String error;
}
