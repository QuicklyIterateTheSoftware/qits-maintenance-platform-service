package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One direct dependency pin, read from one manifest at one revision.
 *
 * <p><b>Direct pins only.</b> A manifest holds what its author wrote down, and only those move; a
 * transitive dependency has no line to edit, so recording one would be an inventory of things this
 * service could never bump.
 *
 * <p><b>{@link #location} is what makes a bump editable.</b> The step that applies a change does
 * not search the file for the old version — it goes to the property, the dependency element or the
 * line number this field names. A blind replace would rewrite the wrong one of two dependencies
 * sharing a version.
 */
@Entity
@Table(name = "mt_pin")
public class MtPin extends PanacheEntityBase {

  @Id public UUID id;

  @Column(nullable = false, length = 255)
  public String repository;

  /** Relative to the repository root: {@code pom.xml}, {@code service/pom.xml}, {@code Dockerfile}. */
  @Column(name = "manifest_path", nullable = false, length = 1024)
  public String manifestPath;

  /** {@code Ecosystem}'s wire name: maven, npm or docker. */
  @Column(nullable = false, length = 32)
  public String ecosystem;

  /** The dependency in its own ecosystem's spelling — what a group's globs match. */
  @Column(nullable = false, length = 512)
  public String name;

  /**
   * What is pinned right now. For npm this is the LOCK's resolved version rather than the range:
   * the range is what the author allowed, the version is what an install actually gets, and only
   * the second can be compared with a registry's latest.
   */
  @Column(nullable = false, length = 255)
  public String version;

  /** The npm range from package.json. Null for maven and docker, which pin exactly. */
  @Column(name = "range", length = 255)
  public String range;

  /** {@code PinKind}'s names: INTERNAL or EXTERNAL. */
  @Column(nullable = false, length = 32)
  public String kind;

  /** Where the version is set: {@code property:<name>}, {@code dependency:<g>:<a>},
   * {@code dependencies}, {@code devDependencies} or {@code line:<n>}. */
  @Column(nullable = false, length = 512)
  public String location;
}
