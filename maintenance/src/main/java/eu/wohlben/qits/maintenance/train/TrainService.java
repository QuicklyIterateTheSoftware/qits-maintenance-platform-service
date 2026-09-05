package eu.wohlben.qits.maintenance.train;

import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.entity.MtTrain;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.RepositoryArchetype;
import eu.wohlben.qits.maintenance.model.TrainEndKind;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.train.ReleaseCoordinates.Coordinate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>The release train: who is expected to adopt a release, decided once, at the release.</b>
 *
 * <p>A repository publishes a version. Some number of other repositories are supposed to end up
 * carrying it, and today that is a thing a person reconstructs by hand out of {@code mt_pin ⋈
 * mt_latest}, one repository at a time, with nothing anywhere saying whether the journey finished.
 * This class writes it down at the moment of the release, as a station ({@code mt_train}) and one
 * node per expected adopter ({@code mt_train_node}).
 *
 * <p><b>WHY THE SET IS FROZEN AT THE RELEASE rather than recomputed on every read.</b> Everything
 * else this service stores about the estate is a CACHE — pins, groups, latest versions, all replaced
 * wholesale by the next scan — and the natural instinct is to make this one too. It cannot be. The
 * expected adopters are who pinned the released coordinate AT THAT MOMENT; a repository that dropped
 * the dependency the following week was still owed the adoption and still never made it, and a
 * recomputed set would quietly erase exactly the history a train exists to keep. So this is a log,
 * and {@link #spawn} is the only thing that writes the membership.
 *
 * <h2>Where the adopters come from: two sources, one union</h2>
 *
 * <ul>
 *   <li><b>The DECLARED side</b> — every repository holding an INTERNAL {@code mt_pin} on one of the
 *       released coordinates ({@code MaintenanceStore.internalPinsOn}). A pin is a line somebody
 *       wrote and somebody can edit, which is what makes adoption possible at all.
 *   <li><b>The EVIDENCE side</b> — every repository whose newest released artifact's bill of
 *       materials names one of the coordinates ({@code MaintenanceStore.dependents}). An SBOM sees
 *       transitives, which no manifest names; a repository shipping an old copy of the released
 *       library through something else is carrying the version about whether it declared it or not.
 * </ul>
 *
 * <p>Neither is derivable from the other — V3's whole argument — so the train takes the union,
 * deduplicated by consumer repository, minus the releasing repository itself. A repository does not
 * adopt its own release: its pin on its own artifact is REACTOR and its SBOM names itself.
 *
 * <p><b>GITLINK is excluded and it is not an oversight.</b> The wrapper repository pins every
 * submodule as a gitlink, so a gitlink-aware derivation would put qits-qits on the train of every
 * release this platform makes. Gitlinks are banked by the wrapper's own release, in bulk, by a
 * mechanism that is not adoption — see the home repository's CLAUDE.md.
 *
 * <h2>What a spawn deliberately does NOT do</h2>
 *
 * <p><b>No outbound call, ever.</b> {@link #spawn} is called from a durable bus listener, INSIDE the
 * claim transaction the eventstream library has open over the frame. An HTTP read from there is the
 * shape V3 refused for SBOM documents: a slow peer becomes an event redelivered for ever with the
 * consumer's watermark stuck behind it. That is why an IMAGE repository's {@code CONFIG_IMAGE_PIN}
 * nodes — which live in qits-configuration's ImagePins map and can only be read over HTTP — are
 * absent from a spawn and are placed afterwards through {@link #recordConfigImagePins}.
 */
@ApplicationScoped
public class TrainService {

  private static final Logger LOG = Logger.getLogger(TrainService.class);

  /**
   * <b>WHICH SERVICE HANDS OUT WHICH DAEMON — the one mapping in this file that is hardcoded, and
   * the place to add the second entry.</b>
   *
   * <p>A daemon is not pinned in any manifest: it is distributed by a service, which records the
   * build it hands out. Nothing in this service's tables says which service that is, and nothing in
   * the catalog does either — the archetype says a repository IS a daemon, not who adopts it. Until
   * that becomes a fact somebody publishes, this map is the fact, and it is spelled out here rather
   * than inferred from the name so that a daemon whose adopter is not its name-prefix sibling does
   * not need a rule change.
   *
   * <p>Keyed by the daemon's REPOSITORY name, valued with the adopting service's repository name.
   */
  static final Map<String, String> DAEMON_ADOPTERS = Map.of("qits-ci-daemon", "qits-ci");

  @Inject MaintenanceStore store;

  @Inject ReleaseCoordinates coordinates;

  /**
   * The evaluation, called at the end of a spawn for the ONE thing only a spawn can do: resolve the
   * nodes elsewhere in the estate whose adoption was this very release and which could not be
   * linked because this train did not exist yet. See {@link TrainEvaluator#arrived}.
   */
  @Inject TrainEvaluator evaluator;

  /**
   * The package one {@code SoftwareRelease} frame announced, when it names one this service can
   * join on.
   *
   * <p>Null at the call site for {@code daemon} and {@code docs} releases: both are real releases of
   * things no manifest anywhere pins, so there is no coordinate to look adopters up by. They still
   * get a station — see {@link #spawn}.
   */
  public record ReleasedPackage(Ecosystem ecosystem, String name) {}

  /**
   * <b>THE STATION FOR ONE RELEASE, opened or settled onto.</b>
   *
   * <p><b>Every packageType of one release folds into the same {@code (repository, version)}
   * train</b>, including {@code docs}: a pipeline publishing a jar, a package, an image and its
   * api-docs emits four frames and they are one release. The first to arrive derives the adopters
   * and opens the station; the rest settle onto it.
   *
   * <p><b>The derivation looks at the WHOLE release, not at this frame's package.</b> It reads every
   * artifact row this repository has at this version and unions in the frame's own coordinate — so
   * whichever of the four frames arrives first, the train is derived from as much of the release as
   * has landed by then, and the frame's own package is covered even if its {@code mt_artifact} row
   * has not been written yet (the two listeners are independent consumers and neither waits for the
   * other).
   *
   * <p><b>A release nobody was expected to adopt still gets a station</b>, COMPLETED at creation. A
   * docs-only release, a first release of a library nothing pins yet, a service nothing depends on —
   * all of them are journeys of length zero, and a train that is absent is indistinguishable from a
   * release this service never heard about.
   *
   * <p>The store owns idempotence, supersession and the empty-train top-up; see {@code
   * MaintenanceStore.spawnTrain}. This method owns the membership.
   *
   * @param repository the releasing repository by CATALOG NAME — the caller resolves the frame's
   *     spelling before it gets here
   * @param version the released version
   * @param announced the coordinate this frame named, or null for {@code daemon} and {@code docs}
   * @param occurredAt the FRAME's moment, never {@code Instant.now()}: it is the key supersession is
   *     decided on
   */
  public MaintenanceStore.TrainSpawn spawn(
      String repository, String version, ReleasedPackage announced, Instant occurredAt) {
    List<MaintenanceStore.NewNode> nodes = expectedAdopters(repository, version, announced);
    MaintenanceStore.TrainSpawn spawned =
        store.spawnTrain(repository, version, occurredAt, nodes);
    if (spawned.created()) {
      LOG.infof(
          "Release train %s for %s %s: %d expected adopter(s), %s",
          spawned.train().id,
          repository,
          version,
          spawned.nodes(),
          spawned.train().status);
    } else {
      LOG.debugf(
          "Release train %s for %s %s was already open; this release's %s package settles onto it",
          spawned.train().id,
          repository,
          version,
          announced == null ? "unpinnable" : announced.ecosystem().wireName());
    }
    // AND THE OTHER SIDE OF THE RACE, which is the only reason a spawn evaluates anything.
    //
    // The artifact rows and the train rows are written by two INDEPENDENT durable consumers of the
    // same event and neither waits for the other, so this release's own SBOM may have been ingested
    // BEFORE this station existed. When it was, the nodes it adopted upstream — "the frontend took
    // the library's version" — were adopted with a null `child_train_id`, because the train the
    // link points at is the one being opened right here. Resolving it from the ingest side alone
    // would leave those nodes stranded on a coin flip.
    //
    // Cheap when there is nothing to do: two indexed reads for a repository nobody's train is
    // waiting on, which is what keeps this inside the claim transaction honest.
    evaluator.arrived(spawned.train());
    return spawned;
  }

  /**
   * <b>THE SWEEP'S SEAM.</b> Places the {@code CONFIG_IMAGE_PIN} nodes a spawn could not, once
   * somebody has asked qits-configuration who pins the released image.
   *
   * <p>Idempotent against {@code (train, consumer, end kind)}, so a sweep that runs every tick
   * places rows on the first pass and none afterwards, and a train that gains a consumer between two
   * passes gains a node.
   *
   * <p>Called from OUTSIDE any bus claim — that is the whole reason it is a second method rather
   * than a branch inside {@link #spawn}.
   *
   * @param applications the consuming APPLICATIONS of the released image, as qits-configuration
   *     names them. Not repository names: a deployment config pins an application's image, and the
   *     two namespaces mostly but not always agree.
   * @return how many nodes this call placed
   */
  public int recordConfigImagePins(UUID trainId, Collection<String> applications) {
    if (trainId == null || applications == null || applications.isEmpty()) {
      return 0;
    }
    Map<String, String> archetypes = archetypes();
    List<MaintenanceStore.NewNode> nodes = new ArrayList<>();
    for (String application : new LinkedHashSet<>(applications)) {
      if (application == null || application.isBlank()) {
        continue;
      }
      nodes.add(
          new MaintenanceStore.NewNode(
              application, archetypes.get(application), TrainEndKind.CONFIG_IMAGE_PIN));
    }
    int placed = store.addTrainNodes(trainId, nodes);
    if (placed > 0) {
      LOG.infof("Train %s gained %d config image-pin node(s)", trainId, placed);
    }
    return placed;
  }

  /**
   * <b>THE TRAINS THE CONFIG-PIN SWEEP HAS TO ASK ABOUT</b>, derived rather than flagged on the row.
   *
   * <p>A train whose releasing repository is an IMAGE is one whose config-pin side may be owed;
   * nothing else can be. No column records the need, deliberately: it is entirely a function of the
   * archetype and of which nodes are already there, and a stored flag would be a third copy of that
   * with its own staleness — the same argument V1 makes for pending not being a table.
   *
   * <p>A train that already carries {@code CONFIG_IMAGE_PIN} nodes is still answered. The sweep is
   * idempotent and a consuming application may have been added to a deployment config since the last
   * pass; filtering them out here would freeze the first answer.
   *
   * <h2>WHY IT IS NOT SIMPLY {@code openTrains()}, WHICH IS THE WHOLE OF THE ORDINARY CASE</h2>
   *
   * <p>An IMAGE repository's release usually has <b>nothing to place at spawn at all</b>: no
   * Dockerfile in the estate says {@code FROM qits/workspace}, so the derivation finds no consumer,
   * and {@code spawnTrain} closes a station with no nodes as COMPLETED at creation — it arrived the
   * instant it left. Asking only the OPEN trains would therefore skip exactly the trains this sweep
   * exists for, every time, and the feature would be dead in the one shape it was built for.
   *
   * <p>So an EMPTY train counts too, and {@code MaintenanceStore.addTrainNodes} already says what
   * happens then: "a COMPLETED train that gains a node is OPEN again … it had arrived because it had
   * nowhere to go, and now it has somewhere."
   *
   * <p><b>Bounded to the NEWEST train of each IMAGE repository</b>, and that bound is the point. An
   * empty station is never superseded — supersession only overtakes OPEN trains — so without it
   * every image release this platform ever made would be re-materialised on every sweep, and a
   * six-month-old version would gain a node that the current pin lands immediately. What a
   * deployment configuration can still be moved to is the latest release; anything older was never
   * going to be deployed now.
   */
  public List<MtTrain> configPinCandidates() {
    Map<String, String> archetypes = archetypes();
    Map<UUID, MtTrain> candidates = new LinkedHashMap<>();
    for (MtTrain train : store.openTrains()) {
      if (image(archetypes, train.repository)) {
        candidates.put(train.id, train);
      }
    }
    for (Map.Entry<String, String> entry : archetypes.entrySet()) {
      if (!image(archetypes, entry.getKey())) {
        continue;
      }
      // One row per IMAGE repository, and there are a handful of them in the whole catalog.
      MtTrain newest = store.trains(entry.getKey(), 1).stream().findFirst().orElse(null);
      if (newest == null || candidates.containsKey(newest.id)) {
        continue;
      }
      if (store.trainNodes(newest.id).isEmpty()) {
        candidates.put(newest.id, newest);
      }
    }
    return List.copyOf(candidates.values());
  }

  private static boolean image(Map<String, String> archetypes, String repository) {
    return RepositoryArchetype.of(archetypes.get(repository))
        .filter(archetype -> archetype == RepositoryArchetype.IMAGE)
        .isPresent();
  }

  // --- the derivation -----------------------------------------------------------------------

  /**
   * Who is expected to adopt this release: the union of the declared and the evidence side, minus
   * the releasing repository, plus whatever end kinds the releasing repository's archetype adds.
   */
  private List<MaintenanceStore.NewNode> expectedAdopters(
      String repository, String version, ReleasedPackage announced) {
    Map<String, String> archetypes = archetypes();
    List<MaintenanceStore.NewNode> nodes = new ArrayList<>();

    Set<Coordinate> publishedCoordinates = releasedCoordinates(repository, version, announced);
    Set<String> consumers = new LinkedHashSet<>();
    for (Coordinate coordinate : publishedCoordinates) {
      for (MtPin pin : store.internalPinsOn(coordinate.ecosystem(), coordinate.name())) {
        consumers.add(pin.repository);
      }
      // The newest released version of each dependent only: a library released fifty times would
      // otherwise put fifty rows of the same repository on one train.
      for (MaintenanceStore.Dependent dependent :
          store.dependents(coordinate.ecosystem(), coordinate.name(), true)) {
        // mt_artifact.repository may still hold another context's spelling on rows written before
        // the listener learned to resolve it; the catalog reads it back as the name this joins on.
        consumers.add(store.repositoryName(dependent.artifact().repository));
      }
    }
    // A repository does not adopt its own release: its pin on its own artifact is REACTOR, and its
    // bill of materials names itself.
    consumers.remove(repository);
    for (String consumer : consumers) {
      if (consumer == null || consumer.isBlank()) {
        continue;
      }
      // A null or unrecognised archetype is plain LINKED semantics — the node is placed, it simply
      // carries no typed reading of what its consumer is.
      nodes.add(new MaintenanceStore.NewNode(consumer, archetypes.get(consumer), TrainEndKind.LINKED));
    }

    RepositoryArchetype released = RepositoryArchetype.of(archetypes.get(repository)).orElse(null);
    if (released == RepositoryArchetype.DAEMON) {
      String adopter = DAEMON_ADOPTERS.get(repository);
      if (adopter == null) {
        LOG.warnf(
            "%s is a DAEMON and no service is recorded as handing it out; its train carries no "
                + "daemon-pin node. Add it to TrainService.DAEMON_ADOPTERS.",
            repository);
      } else {
        // Beside any LINKED node the same repository may already have: one consumer can owe a train
        // both a manifest bump and a daemon pin, and they settle independently.
        nodes.add(
            new MaintenanceStore.NewNode(
                adopter, archetypes.get(adopter), TrainEndKind.DAEMON_PIN));
      }
    }
    // IMAGE is the third case and it is deliberately absent here: its CONFIG_IMAGE_PIN nodes need
    // qits-configuration, and this method runs inside the bus's claim transaction. See
    // recordConfigImagePins.
    return List.copyOf(nodes);
  }

  /**
   * What this release put into a registry, as far as anything here knows.
   *
   * <p>The union of {@link ReleaseCoordinates} — every {@code mt_artifact} row this repository has
   * at this version — and the coordinate the frame itself announced. The union rather than a
   * fallback: the artifact rows are written by a DIFFERENT durable consumer, so this frame's own row
   * may not exist yet — and a sibling frame that arrived first may have written rows this frame
   * knows nothing about. Taking both is the only reading that is right whichever order the two
   * consumers run in.
   *
   * <p>The shared half is shared for a reason: the evaluation joins on exactly the same pairs when
   * it decides whether a consumer's bill of materials carries this release, and a second derivation
   * would be a second chance for the two halves of one train to disagree about what it released.
   *
   * <p>GITLINK never appears: {@code mt_artifact} holds only the three registry ecosystems (V3), and
   * the guard below says so out loud rather than relying on it.
   */
  private Set<Coordinate> releasedCoordinates(
      String repository, String version, ReleasedPackage announced) {
    Set<Coordinate> released = new LinkedHashSet<>(coordinates.of(repository, version));
    if (announced != null
        && announced.ecosystem() != null
        && announced.ecosystem() != Ecosystem.GITLINK
        && announced.name() != null
        && !announced.name().isBlank()) {
      released.add(new Coordinate(announced.ecosystem(), announced.name()));
    }
    return released;
  }

  /**
   * What every repository IS, in one read.
   *
   * <p>One query over the whole inventory rather than a lookup per consumer, which is what {@code
   * mt_repository.archetype} was added for (V6): placement is decided over a set of repositories at
   * once, and asking the catalog — or even this database — per row would be fifty round trips on a
   * path that runs inside somebody else's claim transaction.
   */
  private Map<String, String> archetypes() {
    Map<String, String> archetypes = new LinkedHashMap<>();
    for (MtRepository row : store.repositories()) {
      archetypes.put(row.name, row.archetype);
    }
    return archetypes;
  }
}
