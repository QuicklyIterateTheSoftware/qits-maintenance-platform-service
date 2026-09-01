package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One "A pulled in B" inside one artifact's graph.
 *
 * <p><b>Adjacency, not a closure.</b> The question the UI asks is "what pulled this in", which is a
 * PATH — a nested tree under each direct dependency, walked in memory for one artifact at a time. A
 * transitive closure answers "is B anywhere below A" instead, cannot show the path, and is
 * quadratic in a graph that is re-read wholesale on every ingest.
 *
 * <p><b>A null {@link #parentComponentId} is the ROOT.</b> The document's root component is the
 * artifact itself, which is the {@link MtArtifact} row rather than a component row, so its own
 * dependencies are recorded with no parent instead of pointing at a self-referencing component
 * nothing would ever match.
 */
@Entity
@Table(name = "mt_artifact_edge")
public class MtArtifactEdge extends PanacheEntityBase {

  @Id public UUID id;

  @Column(name = "artifact_id", nullable = false)
  public UUID artifactId;

  /** The {@link MtArtifactComponent} that depends, or null when the artifact itself does. */
  @Column(name = "parent_component_id")
  public UUID parentComponentId;

  /** The {@link MtArtifactComponent} that is depended on. */
  @Column(name = "child_component_id", nullable = false)
  public UUID childComponentId;
}
