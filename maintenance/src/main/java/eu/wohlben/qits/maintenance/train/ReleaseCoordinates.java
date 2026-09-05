package eu.wohlben.qits.maintenance.train;

import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * <b>WHAT ONE RELEASE PUT INTO A REGISTRY</b> — the coordinates both sides of a train join on.
 *
 * <p>A train is a released {@code (repository, version)}, and everything a train has to decide is
 * decided against the {@code (ecosystem, name)} pairs that release published: the spawn asks who
 * PINS one of them ({@link TrainService}), and the evaluation asks whose bill of materials CONTAINS
 * one of them ({@link TrainEvaluator}). Both need the same answer out of the same table, so it is
 * read in one place rather than derived twice with two chances of disagreeing.
 *
 * <p><b>GITLINK never appears.</b> {@code mt_artifact} holds the three registry ecosystems only
 * (V3), and the filter below says so out loud rather than relying on it — a gitlink is pinned by
 * the wrapper, banked in bulk by the wrapper's own release, and is never a train's concern.
 */
@ApplicationScoped
public class ReleaseCoordinates {

  @Inject MaintenanceStore store;

  /** One released package, as both the pin side and the SBOM side of a train name it. */
  public record Coordinate(Ecosystem ecosystem, String name) {}

  /**
   * Every registry coordinate this repository published at this version, as far as anything here
   * knows.
   *
   * <p>Empty is an ordinary answer rather than a gap: a {@code docs}-only release names no
   * coordinate at all, and a release whose {@code mt_artifact} rows have not been written yet is
   * one whose sibling consumer has simply not run — see {@link TrainService#spawn}, which unions
   * the frame's own coordinate in on top of this.
   */
  public Set<Coordinate> of(String repository, String version) {
    Set<Coordinate> coordinates = new LinkedHashSet<>();
    if (repository == null || version == null) {
      return coordinates;
    }
    for (MtArtifact artifact : store.artifactsOfRepository(spellings(repository))) {
      if (!version.equals(artifact.version)) {
        continue;
      }
      Ecosystem.of(artifact.ecosystem)
          .filter(ecosystem -> ecosystem != Ecosystem.GITLINK)
          .ifPresent(ecosystem -> coordinates.add(new Coordinate(ecosystem, artifact.name)));
    }
    return coordinates;
  }

  /**
   * Every string this repository's artifact rows may carry: its catalog name, and the catalog id
   * that rows written before the listener learned to resolve it still hold.
   */
  public List<String> spellings(String repository) {
    Optional<MtRepository> row = store.repository(repository);
    String catalogId = row.map(found -> found.catalogId).orElse(null);
    return catalogId == null || catalogId.isBlank()
        ? List.of(repository)
        : List.of(repository, catalogId);
  }
}
