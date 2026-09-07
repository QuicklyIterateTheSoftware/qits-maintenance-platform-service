package eu.wohlben.qits.maintenance.adoption;

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
 * <b>WHAT ONE RELEASE PUT INTO A REGISTRY</b> — the coordinates both halves of an adoption question
 * join on.
 *
 * <p>A release is a {@code (repository, version)}, and everything asked about one is asked against
 * the {@code (ecosystem, name)} pairs it published: the closure asks who PINS one of them ({@link
 * DownstreamResolver}), and the journey asks whose bill of materials CONTAINS one of them ({@link
 * AdoptionEvaluator}). Both need the same answer out of the same table, so it is read in one place
 * rather than derived twice with two chances of disagreeing.
 *
 * <p><b>GITLINK never appears here.</b> {@code mt_artifact} holds the three registry ecosystems only
 * (V3), and the filter below says so out loud rather than relying on it. The gitlink edge — a
 * frontend that is a service's {@code webui} submodule — is not a released coordinate at all: it is
 * a PIN, and {@link DownstreamResolver} unions it in on the pin side under the repository's own
 * name. Evidence for that edge still rides a registry coordinate; see {@link AdoptionEvaluator}.
 */
@ApplicationScoped
public class ReleaseCoordinates {

  @Inject MaintenanceStore store;

  /** One released package, as both the pin side and the SBOM side name it. */
  public record Coordinate(Ecosystem ecosystem, String name) {}

  /**
   * Every registry coordinate this repository published at this version, as far as anything here
   * knows.
   *
   * <p>Empty is an ordinary answer rather than a gap: a {@code docs}-only release names no
   * coordinate at all, a {@code daemon} release names nothing any manifest pins, and a release whose
   * {@code mt_artifact} rows have not been written yet is one whose sibling consumer has simply not
   * run. Every caller here treats an empty set as "nothing to match on", which is PENDING rather
   * than a refusal.
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
