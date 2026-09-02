package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One thing a released artifact contains, as its CycloneDX document listed it.
 *
 * <p><b>{@link #purl} is kept verbatim.</b> The three columns beside it are this service's READING
 * of that string; the string is the evidence, and a wrong parse is reproducible only if the input
 * survived.
 *
 * <p><b>A null {@link #ecosystem} is a purl type this service does not map</b> — {@code
 * pkg:golang/…}, {@code pkg:generic/…}, a document with no purl at all. Such a component is stored
 * and shown and is never MATCHED: a name in a world this platform does not inventory cannot be
 * compared with a {@code mt_pin} row or a {@code mt_latest} one.
 */
@Entity
@Table(name = "mt_artifact_component")
public class MtArtifactComponent extends PanacheEntityBase {

  @Id public UUID id;

  /** The {@link MtArtifact} this was read out of. A real FK — both ends are this context's. */
  @Column(name = "artifact_id", nullable = false)
  public UUID artifactId;

  /** The document's own identifier for the component, which its {@code dependencies[]} refer to. */
  @Column(name = "bom_ref", length = 1024)
  public String bomRef;

  /** The package url exactly as the document spelled it. */
  @Column(length = 1024)
  public String purl;

  /** {@code Ecosystem}'s wire name, or null when the purl type is one this service does not map. */
  @Column(length = 32)
  public String ecosystem;

  /** The name in {@code mt_pin}'s spelling when the purl mapped, the document's own when it did not. */
  @Column(length = 512)
  public String name;

  @Column(length = 255)
  public String version;

  /**
   * Whether the artifact DECLARES it — a member of the root component's own {@code dependsOn} list.
   * Everything else the graph reaches is transitive, and no line in any manifest names it.
   */
  @Column(nullable = false)
  public boolean direct;
}
