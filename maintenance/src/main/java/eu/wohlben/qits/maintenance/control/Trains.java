package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.maintenance.dto.TrainDto;
import eu.wohlben.qits.maintenance.dto.TrainNodeDto;
import eu.wohlben.qits.maintenance.dto.TrainSummaryDto;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.entity.MtTrain;
import eu.wohlben.qits.maintenance.entity.MtTrainNode;
import eu.wohlben.qits.maintenance.error.NoSuchTrainException;
import eu.wohlben.qits.maintenance.model.TrainNodeState;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.train.ReleaseCoordinates;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The read side of the release trains, beside {@link Inventory}'s read side of the inventory and
 * {@link ArtifactGraph}'s of the dependency graph.
 *
 * <p>It sits in the domain jar for the reason those two do: the shapes are the context's, not the
 * web layer's, and {@code service}'s controllers do routing, roles and status codes and nothing
 * else.
 *
 * <h2>ONE TRAIN PER ANSWER. The journey is stitched by the client.</h2>
 *
 * <p>Every node carries the id of the train its adoption produced, and nothing here follows one. A
 * merged journey has no natural size — a library release reaches the whole estate two hops out —
 * and no natural root, because the same train is a child of one journey and the head of another. A
 * server-side fold would have to invent both, and it would have to invent them the same way for
 * every view that ever reads this. So the API answers stations and the view walks the links to
 * exactly the depth it draws.
 *
 * <h2>Nothing here is stored, and nothing here writes</h2>
 *
 * <p>A train IS a log — {@code TrainService} froze its membership at the release and no scan
 * rewrites it — so unlike the pending counts next door there is nothing to recompute. What IS joined
 * on every read is the two live facts a node row cannot hold: the consumer's current {@code
 * catalog_id}, which is the address of the release request an adoption opened, and its current
 * {@code status}, which is why an adoption may never arrive. See {@link TrainNodeDto}.
 *
 * <h2>The uuid-vs-name hazard, once more</h2>
 *
 * <p>{@code mt_train.repository} is a catalog NAME — the listener resolves the frame's spelling
 * before a row is written — but a CALLER need not know that. qits-projects addresses repositories by
 * row id, and the release-request page that cross-links into here holds an id rather than a name, so
 * {@link #byRelease} translates one through the same {@link RepositoryNames} read {@link
 * ArtifactGraph} uses. Answering 404 to a caller holding the id of the very repository that released
 * would be the same wedge V5 measured, wearing a different route.
 */
@ApplicationScoped
public class Trains {

  @Inject MaintenanceStore store;

  /**
   * What a release PUT INTO A REGISTRY, read through the one class that derives it.
   *
   * <p>Shared with {@code TrainService.spawn} and {@code TrainEvaluator} rather than re-read here:
   * the station's label and the membership it was derived from have to be the same list, and a
   * second reading of {@code mt_artifact} would be a second chance for the two to disagree about
   * what a release published.
   */
  @Inject ReleaseCoordinates coordinates;

  /**
   * The newest trains, of one repository or of all of them — the journey index.
   *
   * <p>The repository filter takes a NAME and tolerates a catalog id, exactly as {@link #byRelease}
   * does and for the same reason: the caller may be holding qits-projects' spelling.
   *
   * <p><b>One nodes read per train, deliberately.</b> This is a page of tens of rows and the two
   * counts are computed from precisely the rows {@link #train} would serve, so a figure here and a
   * list there can never disagree — the same trade {@link ArtifactGraph#artifacts()} makes.
   */
  public List<TrainSummaryDto> trains(String repository, int limit) {
    RepositoryNames names = names();
    List<TrainSummaryDto> listing = new ArrayList<>();
    for (MtTrain train : store.trains(names.of(repository), limit)) {
      List<MtTrainNode> nodes = store.trainNodes(train.id);
      int landed = 0;
      for (MtTrainNode node : nodes) {
        if (TrainNodeState.of(node.state) == TrainNodeState.LANDED) {
          landed++;
        }
      }
      listing.add(
          new TrainSummaryDto(
              train.id,
              train.repository,
              train.version,
              train.status,
              train.createdAt,
              train.completedAt,
              nodes.size(),
              landed));
    }
    return List.copyOf(listing);
  }

  /** One train with every adopter it is owed, and the coordinates that label the station. */
  public TrainDto train(UUID id) {
    MtTrain row = store.train(id).orElseThrow(() -> new NoSuchTrainException(id));
    return detail(row, names());
  }

  /**
   * The same train, addressed the way the release-request page holds it: a repository and a version.
   *
   * <p><b>404 means "this release has no station", which is an ordinary answer</b> rather than a
   * fault — a release published before trains existed, or one this service never saw. It is a
   * different thing from a train with no nodes, which is a journey of length zero and answers 200
   * with an empty list.
   *
   * @param repository the releasing repository by NAME, or qits-projects' catalog id for it
   */
  public TrainDto byRelease(String repository, String version) {
    RepositoryNames names = names();
    MtTrain row =
        store
            .train(names.of(repository), version)
            .orElseThrow(() -> new NoSuchTrainException(repository, version));
    return detail(row, names);
  }

  // --- the shared readings ------------------------------------------------------------------

  private TrainDto detail(MtTrain train, RepositoryNames names) {
    List<TrainNodeDto> nodes = new ArrayList<>();
    for (MtTrainNode node : store.trainNodes(train.id)) {
      nodes.add(node(node, names));
    }
    List<TrainDto.PackageDto> packages = new ArrayList<>();
    for (ReleaseCoordinates.Coordinate coordinate :
        coordinates.of(train.repository, train.version)) {
      packages.add(
          new TrainDto.PackageDto(coordinate.ecosystem().wireName(), coordinate.name()));
    }
    return new TrainDto(
        train.id,
        train.repository,
        train.version,
        train.status,
        train.createdAt,
        train.completedAt,
        train.supersededBy,
        List.copyOf(packages),
        List.copyOf(nodes));
  }

  /**
   * One node, with the two live facts about its consumer joined on.
   *
   * <p>A consumer that resolves to no row leaves both null, and that is the ORDINARY shape for a
   * {@code CONFIG_IMAGE_PIN} node: its consumer is an application name, and applications are not
   * repositories. It is also what an unscanned repository looks like, and the two are indeed the
   * same statement from here — this inventory knows nothing about the name.
   */
  private static TrainNodeDto node(MtTrainNode node, RepositoryNames names) {
    MtRepository consumer = names.row(node.consumer);
    return new TrainNodeDto(
        node.id,
        node.consumer,
        consumer == null ? null : consumer.catalogId,
        consumer == null ? null : consumer.status,
        node.archetype,
        node.endKind,
        node.state,
        node.adoptedVersion,
        node.adoptedAt,
        node.childTrainId,
        node.landedAt);
  }

  // --- the one translation ------------------------------------------------------------------

  /**
   * The catalog read every answer here starts from: {@code mt_repository}, keyed both ways.
   *
   * <p><b>ONE READ per answer, never one per node.</b> The catalog is tens of rows and every node of
   * every train names one of them, which is the same argument {@link ArtifactGraph} makes for its
   * own copy of this.
   */
  private RepositoryNames names() {
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
    return new RepositoryNames(nameByCatalogId, byName);
  }

  /**
   * The inventory, in the two shapes this class reads it in: a spelling turned into a name, and a
   * name turned into its row.
   *
   * <p><b>An unknown value passes through untouched</b>, exactly as it does in {@link
   * ArtifactGraph}. A caller naming a repository this inventory has never scanned still named
   * something, and a train may well be keyed by that very string — {@code MtTrain.repository} keeps
   * an unknown spelling verbatim rather than dropping it.
   */
  private record RepositoryNames(
      Map<String, String> nameByCatalogId, Map<String, MtRepository> byName) {

    /** A caller's spelling of a repository, as this store keys one. */
    String of(String spelling) {
      if (spelling == null) {
        return null;
      }
      return nameByCatalogId.getOrDefault(spelling, spelling);
    }

    /** One consumer's inventory row, or null when nothing here knows the name. */
    MtRepository row(String consumer) {
      return consumer == null ? null : byName.get(consumer);
    }
  }
}
