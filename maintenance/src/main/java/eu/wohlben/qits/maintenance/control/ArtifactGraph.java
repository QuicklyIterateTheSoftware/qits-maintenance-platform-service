package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.maintenance.dto.ArtifactDto;
import eu.wohlben.qits.maintenance.dto.DependentDto;
import eu.wohlben.qits.maintenance.dto.DependentsDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDependentsDto;
import eu.wohlben.qits.maintenance.dto.TransitiveDto;
import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtArtifactComponent;
import eu.wohlben.qits.maintenance.entity.MtArtifactEdge;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The read side of what our releases CONTAIN, beside {@link Inventory}'s read side of what our
 * manifests DECLARE.
 *
 * <p><b>Two facts, one join key, and they never merge.</b> An SBOM says what a released artifact
 * contains; {@code mt_pin} says what a bump edits. They are related by {@code (ecosystem, name)} and
 * by nothing else — an SBOM cannot name a pom property, and a pin cannot see a transitive. Every
 * method here reaches across that join and none of them writes through it.
 *
 * <p><b>The default view is the NEWEST released version of each dependent.</b> A library released
 * fifty times would otherwise answer "who ships me" fifty times over, and forty-nine of those
 * answers are about versions nobody can do anything about any more. {@code all=true} is the
 * archaeology.
 *
 * <p><b>Nothing here is stored.</b> Dependent counts, "behind" verdicts and the {@code via} paths
 * are computed on every read, for the reason pending is: they are a join of two halves that move on
 * different schedules, and a stored copy would be stale between the release that moved one and the
 * lookup that moved the other.
 */
@ApplicationScoped
public class ArtifactGraph {

  @Inject MaintenanceStore store;

  // --- who ships a copy of this ----------------------------------------------------------------

  /**
   * Every artifact of ours whose bill of materials names the dependency.
   *
   * @param all every ingested version rather than the newest of each dependent
   */
  public DependentsDto dependents(Ecosystem ecosystem, String name, boolean all) {
    List<DependentDto> dependents =
        store.dependents(ecosystem, name, !all).stream().map(ArtifactGraph::dependent).toList();
    String latest = store.latest(ecosystem, name).map(row -> row.latest).orElse(null);
    return new DependentsDto(ecosystem.wireName(), name, latest, dependents);
  }

  /**
   * The same list for every artifact one repository produced, grouped by which artifact.
   *
   * <p>"Who is affected if I release this" is a question about the repository, and a repository
   * publishing a jar, an npm package and an image out of one reactor is the ordinary shape here.
   */
  public RepositoryDependentsDto repositoryDependents(String repository) {
    List<RepositoryDependentsDto.ArtifactDependentsDto> artifacts = new ArrayList<>();
    for (MtArtifact artifact : newestPerName(store.artifactsOfRepository(repository), false)) {
      Optional<Ecosystem> ecosystem = Ecosystem.of(artifact.ecosystem);
      if (ecosystem.isEmpty()) {
        continue;
      }
      artifacts.add(
          new RepositoryDependentsDto.ArtifactDependentsDto(
              artifact.ecosystem,
              artifact.name,
              store.dependents(ecosystem.get(), artifact.name, true).stream()
                  .map(ArtifactGraph::dependent)
                  .toList()));
    }
    return new RepositoryDependentsDto(repository, List.copyOf(artifacts));
  }

  // --- what this platform publishes -------------------------------------------------------------

  /**
   * Every distinct artifact this platform has released, with how far its reach goes and how much of
   * that reach is stale.
   *
   * <p><b>Straightforward reads rather than one clever query.</b> This is a page of tens of rows,
   * not thousands, and the two counts are computed from exactly the same newest-per-dependent view
   * the dependents route serves — so a figure here and a list there can never disagree.
   */
  public List<ArtifactDto> artifacts() {
    List<ArtifactDto> listing = new ArrayList<>();
    for (MtArtifact artifact : store.newestArtifactPerName()) {
      Optional<Ecosystem> ecosystem = Ecosystem.of(artifact.ecosystem);
      String latest =
          ecosystem
              .flatMap(value -> store.latest(value, artifact.name))
              .map(row -> row.latest)
              .orElse(null);
      int dependentCount = 0;
      int behindCount = 0;
      if (ecosystem.isPresent()) {
        List<MaintenanceStore.Dependent> dependents =
            store.dependents(ecosystem.get(), artifact.name, true);
        dependentCount = dependents.size();
        for (MaintenanceStore.Dependent dependent : dependents) {
          if (behind(ecosystem.get(), dependent.component().version, latest)) {
            behindCount++;
          }
        }
      }
      listing.add(
          new ArtifactDto(
              artifact.ecosystem,
              artifact.name,
              artifact.repository,
              latest,
              artifact.version,
              artifact.occurredAt,
              artifact.sbomStatus,
              dependentCount,
              behindCount));
    }
    return List.copyOf(listing);
  }

  // --- what one repository's releases contain ---------------------------------------------------

  /**
   * The transitive half of a repository's detail page: what its released artifacts contain that no
   * manifest of theirs names.
   *
   * <p><b>Anything that is also a pin is removed.</b> A component the repository declares is
   * already on the page with a version, a latest and a verdict; repeating it as a transitive would
   * say the opposite of what is true about it.
   *
   * <p><b>Empty is "we do not know", not "there are none".</b> A repository whose artifacts have no
   * stored SBOM — the ordinary state during the rollout — has nothing here, and so does one whose
   * releases genuinely contain nothing.
   */
  public List<TransitiveDto> transitives(String repository, List<MtPin> pins) {
    Set<String> pinned = new HashSet<>();
    for (MtPin pin : pins) {
      pinned.add(PendingChanges.key(pin.ecosystem, pin.name));
    }

    // Keyed so the same transitive reached through two of the repository's own artifacts is one
    // row. The first reading wins, which the ordering below makes deterministic.
    Map<String, TransitiveDto> found = new LinkedHashMap<>();
    for (MtArtifact artifact : newestPerName(store.artifactsOfRepository(repository), true)) {
      for (TransitiveDto transitive : transitivesOf(artifact)) {
        if (pinned.contains(PendingChanges.key(transitive.ecosystem(), transitive.name()))) {
          continue;
        }
        found.putIfAbsent(
            transitive.ecosystem() + " " + transitive.name() + " " + transitive.version(),
            transitive);
      }
    }
    List<TransitiveDto> listing = new ArrayList<>(found.values());
    listing.sort(
        Comparator.comparing(TransitiveDto::name, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(
                TransitiveDto::version, Comparator.nullsLast(Comparator.naturalOrder())));
    return List.copyOf(listing);
  }

  /** One artifact's transitives, each with the direct component whose subtree pulled it in. */
  private List<TransitiveDto> transitivesOf(MtArtifact artifact) {
    List<MtArtifactComponent> components = store.components(artifact.id);
    if (components.isEmpty()) {
      return List.of();
    }
    Map<UUID, MtArtifactComponent> byId = new LinkedHashMap<>();
    for (MtArtifactComponent component : components) {
      byId.put(component.id, component);
    }
    List<MtArtifactEdge> edges = store.edges(artifact.id);
    Map<UUID, List<UUID>> children = new LinkedHashMap<>();
    Set<UUID> rootChildren = new LinkedHashSet<>();
    for (MtArtifactEdge edge : edges) {
      if (edge.parentComponentId == null) {
        rootChildren.add(edge.childComponentId);
      } else {
        children.computeIfAbsent(edge.parentComponentId, key -> new ArrayList<>())
            .add(edge.childComponentId);
      }
    }

    Map<UUID, String> via = viaByComponent(components, children, byId);

    List<TransitiveDto> listing = new ArrayList<>();
    for (MtArtifactComponent component : components) {
      if (component.direct) {
        continue;
      }
      // A component the ROOT itself names is nobody's transitive dependency even when the document
      // did not list it as direct — there is no `via` to report, because nothing pulled it in.
      String path = rootChildren.contains(component.id) ? null : via.get(component.id);
      Optional<Ecosystem> ecosystem = Ecosystem.of(component.ecosystem);
      boolean behind =
          ecosystem
              .map(
                  value ->
                      behind(
                          value,
                          component.version,
                          store.latest(value, component.name).map(row -> row.latest).orElse(null)))
              .orElse(false);
      listing.add(
          new TransitiveDto(component.ecosystem, component.name, component.version, path, behind));
    }
    return listing;
  }

  /**
   * The direct component whose subtree reaches each component.
   *
   * <p><b>The first by name where several do, and a forward walk is what makes that cheap.</b> A
   * graph has many paths to one node and a page needs one; walking down from each direct in name
   * order and claiming whatever is not claimed yet gives the same answer every time, with no
   * ordering left to the database.
   */
  private static Map<UUID, String> viaByComponent(
      List<MtArtifactComponent> components,
      Map<UUID, List<UUID>> children,
      Map<UUID, MtArtifactComponent> byId) {
    List<MtArtifactComponent> directs =
        components.stream()
            .filter(component -> component.direct)
            .sorted(
                Comparator.comparing(
                    component -> component.name, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

    Map<UUID, String> via = new LinkedHashMap<>();
    for (MtArtifactComponent direct : directs) {
      Deque<UUID> queue = new ArrayDeque<>(children.getOrDefault(direct.id, List.of()));
      // Per-direct, so a cycle in somebody's document cannot spin here — and cycles do occur.
      Set<UUID> seen = new HashSet<>();
      seen.add(direct.id);
      while (!queue.isEmpty()) {
        UUID next = queue.poll();
        if (!seen.add(next) || !byId.containsKey(next)) {
          continue;
        }
        via.putIfAbsent(next, direct.name);
        queue.addAll(children.getOrDefault(next, List.of()));
      }
    }
    return via;
  }

  // --- the shared readings ----------------------------------------------------------------------

  /**
   * The newest row per {@code (ecosystem, name)} out of a list already ordered newest-first within
   * each pair.
   *
   * @param ingestedOnly the newest row whose document was actually READ, which is a different row
   *     from the newest release whenever the last one has no SBOM stored
   */
  private static List<MtArtifact> newestPerName(List<MtArtifact> ordered, boolean ingestedOnly) {
    List<MtArtifact> newest = new ArrayList<>();
    Set<String> taken = new LinkedHashSet<>();
    for (MtArtifact row : ordered) {
      if (ingestedOnly && SbomStatus.of(row.sbomStatus) != SbomStatus.INGESTED) {
        continue;
      }
      if (taken.add(row.ecosystem + " " + row.name)) {
        newest.add(row);
      }
    }
    return newest;
  }

  /**
   * Whether an embedded version is older than the newest published one.
   *
   * <p><b>False whenever anything is unknown</b>, which is the same stance the pending rule takes: a
   * lookup that never ran must not read like a green tick OR like an arrow. It is the {@code
   * latestError} column that says "we could not find out", and it is on the dependency, not here.
   */
  private static boolean behind(Ecosystem ecosystem, String embedded, String latest) {
    if (embedded == null || latest == null) {
      return false;
    }
    return VersionOrder.newer(ecosystem, embedded, latest);
  }

  private static DependentDto dependent(MaintenanceStore.Dependent dependent) {
    MtArtifact artifact = dependent.artifact();
    return new DependentDto(
        artifact.ecosystem,
        artifact.name,
        artifact.version,
        artifact.repository,
        dependent.component().version,
        dependent.component().direct,
        artifact.occurredAt,
        artifact.sbomStatus);
  }
}
