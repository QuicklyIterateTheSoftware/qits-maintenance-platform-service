package eu.wohlben.qits.maintenance.adoption;

import eu.wohlben.qits.maintenance.adoption.DownstreamResolver.Closure;
import eu.wohlben.qits.maintenance.adoption.DownstreamResolver.Downstream;
import eu.wohlben.qits.maintenance.adoption.ReleaseCoordinates.Coordinate;
import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>HOW FAR ONE RELEASE ACTUALLY GOT — answered from the graph at query time, over the closure
 * {@link DownstreamResolver} traced.</b>
 *
 * <p>The closure says who COULD adopt. This says who DID, and it says it by reading bills of
 * materials rather than pins — the same rule the retired release train evaluated by, kept verbatim
 * because it was the half of that feature that was right.
 *
 * <h2>The evidence is a BILL OF MATERIALS, not a pin</h2>
 *
 * <p>A repository has adopted a release when its OWN release contains the released coordinate at a
 * version at least the released one. Not when its manifest pin moved: a pin that moved on a branch
 * is a fact about somebody's working tree, nothing polls it, and it is revertible. A released
 * artifact whose SBOM names the coordinate is a fact about a registry, and it is immutable.
 *
 * <p><b>At least, not exactly.</b> The comparison is inclusive on purpose: a consumer that took the
 * released version has adopted it, and one that skipped straight past it to the release after has
 * adopted it too. A component BELOW it is the consumer still carrying the old copy, which is
 * precisely the state this question exists to show.
 *
 * <p><b>A component with no ecosystem never matches</b> — {@code pkg:golang/…},
 * {@code pkg:generic/…}, a document with no purl. Same rule as the rest of the graph: a name in a
 * world this platform does not inventory cannot be compared with anything here. It falls out of the
 * shape below rather than needing a guard, because every lookup is keyed by an {@link Ecosystem}.
 *
 * <h2>{@code adoptedVersion} IS THE ADOPTER'S OWN RELEASE, never the dependency version it took</h2>
 *
 * <p>The matched component's version is what PROVES the adoption and is deliberately not what is
 * reported. What comes out is the adopter's own released version, because that is what the value is
 * read by: it is half of the address of the release request that adoption opened, and that resolver
 * matches the ADOPTER's own releases on version. A dependency version there resolves to nothing, or
 * worse to an unrelated release that happens to share a number.
 *
 * <p><b>The EARLIEST matching release wins</b>, by {@code occurred_at}. A library released in March
 * that the frontend took in April and has shipped in twenty releases since was adopted in April —
 * the newest release carrying it is not when it arrived.
 *
 * <h2>The chain, and why a PENDING parent stops it</h2>
 *
 * <p>A journey is walked over the closure's parent edges, nearest hop first. The root's requirement
 * is the released version itself. A downstream that adopted it at its own version <b>Vₑ</b> then
 * becomes the requirement for ITS children — they have to be carrying Vₑ or later of what it
 * published, which is the next link of the same chain.
 *
 * <p>So a child of a PENDING parent is PENDING, always, and that is not a shortcut: there is no
 * version of the parent to require yet. It is also the honest answer — a service cannot be shipping
 * a library through a frontend that has not shipped the library.
 *
 * <p><b>TWO STATES, and the third is gone with the log.</b> The train had PENDING → ADOPTED →
 * LANDED, where LANDED meant "and the adopting release itself arrived everywhere". That fold only
 * existed because the train was a stored graph of stations that had to be closed; here the whole
 * chain is recomputed on every read, so a repository's own state is exactly what its own evidence
 * says and every hop beyond it is another row in the same answer.
 *
 * <h2>Gitlink edges are honest about what they cannot see</h2>
 *
 * <p>A frontend reaches its service through a GITLINK pin, which is a submodule and never a
 * registry coordinate. The EDGE is real and the closure has it; the EVIDENCE still has to ride a
 * registry coordinate — a service's SBOM naming the frontend's npm bundle. Where the embedder's
 * document does not name the submodule's package, the honest answer is PENDING, and it will stay
 * PENDING however many times it is asked. That is a gap in what is published, not a defect here.
 */
@ApplicationScoped
public class AdoptionEvaluator {

  @Inject MaintenanceStore store;

  @Inject ReleaseCoordinates coordinates;

  @Inject DownstreamResolver downstream;

  /** Whether a downstream repository is carrying the release yet. Two states, and no third. */
  public enum State {
    /** Its own release contains the coordinate at or above the required version. */
    ADOPTED,
    /** It does not, or nothing upstream of it does yet. */
    PENDING
  }

  /**
   * One downstream repository and what became of the release at it.
   *
   * @param repositoryStatus the consumer's CURRENT inventory status, joined live — {@code ABSENT}
   *     beside a PENDING row says the journey is waiting on something the catalog no longer lists
   * @param adoptedVersion the adopter's OWN released version that carries it, null while PENDING
   * @param adoptedAt when that release was published — the publisher's moment off the artifact row,
   *     never a clock reading taken here
   */
  public record Adopter(
      String repository,
      String catalogId,
      String repositoryStatus,
      String archetype,
      int depth,
      List<String> via,
      State state,
      String adoptedVersion,
      Instant adoptedAt) {}

  /**
   * One release, and everywhere it did or did not get to.
   *
   * @param packages what the release put into a registry. Empty is ordinary — a {@code docs}-only
   *     release names no coordinate, nothing pins a daemon, and a release this service never heard
   *     of has no rows — and it means every downstream row below is PENDING, which is true.
   */
  public record Journey(
      String repository,
      String catalogId,
      String version,
      List<Coordinate> packages,
      List<Adopter> adopters) {}

  /**
   * The journey of one released {@code (repository, version)}.
   *
   * <p><b>There is no "no such release" here and that is deliberate.</b> The train answered 404 for
   * a release with no station, which was a fact about the log rather than about the release. An ad
   * hoc answer always exists: an unknown release publishes no coordinate anybody could be carrying,
   * so its closure is real and every row of it is PENDING.
   *
   * @param spelling the releasing repository by catalog name or by catalog id
   */
  public Journey of(String spelling, String version) {
    Closure closure = downstream.of(spelling);
    String root = closure.repository();
    Set<Coordinate> released = coordinates.of(root, version);

    Names names = names();
    // THE REQUIREMENT AT EACH HOP, keyed by the repository that carries it: what a child of that
    // repository has to be shipping. The root's is the release being asked about; a downstream's is
    // its own adopting release, filled in below as the walk decides it.
    Map<String, Requirement> requirements = new LinkedHashMap<>();
    requirements.put(root, new Requirement(released, version));

    List<Adopter> adopters = new ArrayList<>();
    // Depth ascending already, and `via` only ever names the level above — see DownstreamResolver —
    // so every parent of an entry has been decided by the time the entry is reached.
    for (Downstream entry : closure.downstream()) {
      Match match = adopted(entry.repository(), entry.via(), requirements, names);
      MtRepository row = names.row(entry.repository());
      if (match == null) {
        adopters.add(
            new Adopter(
                entry.repository(),
                entry.catalogId(),
                row == null ? null : row.status,
                entry.archetype(),
                entry.depth(),
                entry.via(),
                State.PENDING,
                null,
                null));
        continue;
      }
      // AND THIS IS WHAT THE NEXT HOP HAS TO CARRY. The chain is one requirement per link, and each
      // link's version is the adopter's own — which is the only version anything downstream of it
      // could possibly be shipping.
      requirements.put(
          entry.repository(),
          new Requirement(coordinates.of(entry.repository(), match.version()), match.version()));
      adopters.add(
          new Adopter(
              entry.repository(),
              entry.catalogId(),
              row == null ? null : row.status,
              entry.archetype(),
              entry.depth(),
              entry.via(),
              State.ADOPTED,
              match.version(),
              match.occurredAt()));
    }

    return new Journey(
        root, closure.catalogId(), version, List.copyOf(released), List.copyOf(adopters));
  }

  // --- one repository's verdict -----------------------------------------------------------------

  /** What a child of one repository has to be carrying: its coordinates, at this version. */
  private record Requirement(Set<Coordinate> coordinates, String version) {}

  /** The adopting release, as it is reported: the ADOPTER's own version and its moment. */
  private record Match(String version, Instant occurredAt) {}

  /**
   * The earliest release of this repository that carries any of its parents' requirements.
   *
   * <p><b>Every parent is tried and the earliest match across all of them wins.</b> Two upstreams
   * can both lead here — a service that pins a library directly AND submodules a frontend carrying
   * it — and the question "when did this repository start shipping it" has one answer, not one per
   * path.
   *
   * <p>Null when nothing matches, which covers every honest kind of "not yet": a parent that is
   * itself PENDING contributes no requirement at all, a release that published no coordinate leaves
   * nothing to look for, and a repository whose own documents have not been ingested has no
   * component rows to be found in.
   */
  private Match adopted(
      String repository, List<String> via, Map<String, Requirement> requirements, Names names) {
    Match earliest = null;
    for (String parent : via) {
      Requirement requirement = requirements.get(parent);
      if (requirement == null || requirement.version() == null) {
        // A PENDING parent. There is no version of it to require, so this path says nothing — and if
        // every path says nothing, this repository is PENDING too.
        continue;
      }
      for (Coordinate coordinate : requirement.coordinates()) {
        Match match = carrying(repository, coordinate, requirement.version(), names);
        if (match != null && (earliest == null || match.occurredAt().isBefore(earliest.occurredAt()))) {
          earliest = match;
        }
      }
    }
    return earliest;
  }

  /**
   * The earliest ingested release of this repository whose document names the coordinate at or
   * above the required version.
   *
   * <p><b>One {@code dependents(…, false)} read per coordinate — the ARCHAEOLOGY view, not the
   * default one.</b> The default answers the newest release of each dependent, which is precisely
   * the wrong row here: "when did they take it" is a question about the FIRST release that carried
   * it, and the newest one buries that. It is also the cost ceiling of this whole answer — one
   * indexed read per coordinate per hop — which is fine at the size of this estate and is the thing
   * to look at first if it ever is not.
   */
  private Match carrying(
      String repository, Coordinate coordinate, String requiredVersion, Names names) {
    Match earliest = null;
    for (MaintenanceStore.Dependent dependent :
        store.dependents(coordinate.ecosystem(), coordinate.name(), false)) {
      MtArtifact artifact = dependent.artifact();
      if (artifact.occurredAt == null || SbomStatus.of(artifact.sbomStatus) != SbomStatus.INGESTED) {
        // A row that is PENDING, MISSING or FAILED holds no components, so it is evidence of
        // nothing — including of a NON-adoption.
        continue;
      }
      if (!repository.equals(names.of(artifact.repository))) {
        continue;
      }
      if (!atLeast(coordinate.ecosystem(), dependent.component().version, requiredVersion)) {
        continue;
      }
      if (earliest == null || artifact.occurredAt.isBefore(earliest.occurredAt())) {
        earliest = new Match(artifact.version, artifact.occurredAt);
      }
    }
    return earliest;
  }

  /**
   * The inclusive comparison, in the ecosystem's own order — {@code VersionOrder.comparator}, the
   * same one the pending rule and the forward-only latest guard make.
   *
   * <p>Anything missing is NOT a match: a component with no version proves nothing, and a
   * requirement with no version is not a requirement.
   */
  private static boolean atLeast(Ecosystem ecosystem, String embedded, String required) {
    if (embedded == null || embedded.isBlank() || required == null || required.isBlank()) {
      return false;
    }
    return VersionOrder.comparator(ecosystem).compare(embedded, required) >= 0;
  }

  /**
   * {@code mt_repository} in the two shapes this answer reads it in, in ONE query.
   *
   * <p><b>Never {@code store.repositoryName} per row.</b> A journey walks the archaeology view of
   * every coordinate at every hop — hundreds of rows — and each of those carries a spelling that has
   * to be resolved. One catalog read of tens of rows answers all of them.
   */
  private Names names() {
    Map<String, String> nameByCatalogId = new LinkedHashMap<>();
    Map<String, MtRepository> byName = new LinkedHashMap<>();
    for (MtRepository row : store.repositories()) {
      if (row.name == null) {
        continue;
      }
      byName.put(row.name, row);
      if (row.catalogId != null && !row.catalogId.isBlank()) {
        nameByCatalogId.put(row.catalogId, row.name);
      }
    }
    return new Names(nameByCatalogId, byName);
  }

  /**
   * The inventory, keyed both ways, with the same total-function stance every other translation in
   * this service takes: an unknown spelling passes through untouched, and an unknown name has no
   * row rather than no answer.
   */
  private record Names(Map<String, String> nameByCatalogId, Map<String, MtRepository> byName) {

    /** A stored {@code mt_artifact.repository}, as a repository is CALLED. */
    String of(String stored) {
      if (stored == null) {
        return null;
      }
      return nameByCatalogId.getOrDefault(stored, stored);
    }

    /** One repository's inventory row, or null when nothing here knows the name. */
    MtRepository row(String name) {
      return name == null ? null : byName.get(name);
    }
  }
}
