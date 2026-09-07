package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.maintenance.adoption.AdoptionEvaluator;
import eu.wohlben.qits.maintenance.adoption.DownstreamResolver;
import eu.wohlben.qits.maintenance.adoption.ReleaseCoordinates;
import eu.wohlben.qits.maintenance.dto.AdoptionJourneyDto;
import eu.wohlben.qits.maintenance.dto.DownstreamDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * The read side of "who is downstream, and did they take it", beside {@link Inventory}'s read side
 * of the inventory and {@link ArtifactGraph}'s of the dependency graph.
 *
 * <p>It sits in the domain jar for the reason those two do: the shapes are the context's, not the
 * web layer's, and {@code service}'s controllers do routing, roles and status codes and nothing
 * else.
 *
 * <h2>Nothing here is stored and nothing here writes</h2>
 *
 * <p>This replaces the read side of the release trains, which read a LOG — rows frozen at the
 * release, in the two tables V8 dropped. Both answers below are computed on every read out of
 * {@code mt_pin} and the SBOM graph, for the reason pending counts are: they are a join of two
 * halves that move on different schedules, and a stored copy is stale between the release that
 * moved one and the scan that moved the other. It is also what fixes the bug the trains had — a
 * membership derived once, one hop deep, could never name the service behind the frontend.
 *
 * <h2>The uuid-vs-name duality, once more</h2>
 *
 * <p>Every repository this inventory holds is known under two spellings: its catalog NAME, which is
 * what every join here is keyed by, and qits-projects' row id, which is what a CALLER is likely to
 * be holding — the release-request page has nothing else, and qits-projects' own adapter addresses
 * this route by the repository row id it already has. Both routes take either, resolved inside the
 * walk against one read of {@code mt_repository}. Answering 404 to a caller holding the id of the
 * very repository that released would be the wedge V5 measured, wearing a different route.
 */
@ApplicationScoped
public class Adoption {

  @Inject DownstreamResolver resolver;

  @Inject AdoptionEvaluator evaluator;

  /**
   * <b>Contract A</b> — everything downstream of one repository, ordered upstream first.
   *
   * @param spelling the repository by catalog name or by catalog id
   */
  public DownstreamDto downstream(String spelling) {
    DownstreamResolver.Closure closure = resolver.of(spelling);
    List<DownstreamDto.EntryDto> entries = new ArrayList<>();
    for (DownstreamResolver.Downstream entry : closure.downstream()) {
      entries.add(
          new DownstreamDto.EntryDto(
              entry.repository(),
              entry.catalogId(),
              entry.archetype(),
              entry.depth(),
              entry.via()));
    }
    return new DownstreamDto(closure.repository(), closure.catalogId(), List.copyOf(entries));
  }

  /**
   * How far one released {@code (repository, version)} got.
   *
   * <p><b>No refusal for an unknown release</b> — see {@link AdoptionJourneyDto}. The closure is
   * real whatever was released; a release nothing here knows simply published no coordinate for
   * anybody to be carrying.
   *
   * @param spelling the releasing repository by catalog name or by catalog id
   */
  public AdoptionJourneyDto byRelease(String spelling, String version) {
    AdoptionEvaluator.Journey journey = evaluator.of(spelling, version);

    List<AdoptionJourneyDto.PackageDto> packages = new ArrayList<>();
    for (ReleaseCoordinates.Coordinate coordinate : journey.packages()) {
      packages.add(
          new AdoptionJourneyDto.PackageDto(
              coordinate.ecosystem().wireName(), coordinate.name()));
    }

    List<AdoptionJourneyDto.AdopterDto> adopters = new ArrayList<>();
    for (AdoptionEvaluator.Adopter adopter : journey.adopters()) {
      adopters.add(
          new AdoptionJourneyDto.AdopterDto(
              adopter.repository(),
              adopter.catalogId(),
              adopter.repositoryStatus(),
              adopter.archetype(),
              adopter.depth(),
              adopter.via(),
              adopter.state().name(),
              adopter.adoptedVersion(),
              adopter.adoptedAt()));
    }

    return new AdoptionJourneyDto(
        journey.repository(),
        journey.catalogId(),
        journey.version(),
        List.copyOf(packages),
        List.copyOf(adopters));
  }
}
