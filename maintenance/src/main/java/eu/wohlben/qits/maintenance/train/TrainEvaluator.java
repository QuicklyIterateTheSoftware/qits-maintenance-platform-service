package eu.wohlben.qits.maintenance.train;

import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtArtifactComponent;
import eu.wohlben.qits.maintenance.entity.MtTrain;
import eu.wohlben.qits.maintenance.entity.MtTrainNode;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.model.TrainNodeState;
import eu.wohlben.qits.maintenance.model.TrainStatus;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.train.ReleaseCoordinates.Coordinate;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>WHAT MOVES A TRAIN NODE: PENDING → ADOPTED → LANDED, and the completion that cascades out of
 * the last one.</b>
 *
 * <p>{@link TrainService} writes down who is EXPECTED to adopt a release, once, at the release.
 * This class is the other half — the reading of evidence that says somebody actually did, and the
 * arithmetic that turns "every node landed" into a train that arrived and then into the landings
 * that follows from it further up.
 *
 * <h2>The evidence is a BILL OF MATERIALS, not a pin</h2>
 *
 * <p>A node is ADOPTED when the consumer's own RELEASE contains the released coordinate at a
 * version at least the train's. Not when its manifest pin moved: a pin that moved on a branch is a
 * fact about somebody's working tree, nothing polls it, and it is revertible. A released artifact
 * whose SBOM names the coordinate is a fact about a registry, it is immutable, and it is already
 * arriving here on the bus for another reason (V3). So the hook is the SBOM ingest, and it is
 * exactly one line at the end of it — same {@code work/WorkQueue} thread, same single writer, no
 * new concurrency story.
 *
 * <p><b>At least, not exactly.</b> The comparison is inclusive on purpose: a consumer that took the
 * released version has adopted it, and a consumer that skipped straight past it to the release
 * after has adopted it too — the node this closes is still this train's. A component BELOW the
 * train's version is the consumer still carrying the old copy, which is precisely the state a train
 * exists to show.
 *
 * <p><b>A component with no ecosystem never matches</b> — {@code pkg:golang/…},
 * {@code pkg:generic/…}, a document with no purl. Same rule as the rest of the graph
 * ({@code MtArtifactComponent}): a name in a world this platform does not inventory cannot be
 * compared with anything here.
 *
 * <h2>{@code adopted_version} IS THE CONSUMER'S OWN RELEASE, NOT THE DEPENDENCY VERSION IT TOOK</h2>
 *
 * <p>The matched component's version is what proves the adoption, and it is deliberately NOT what is
 * written down. What goes into the column is {@code artifact.version} — the version of the
 * CONSUMER's release that carries the evidence — because that is the only value the column is read
 * by: the detail view composes the adopting release's request link as {@code
 * release-requests/by-release/<consumer catalog id>/<adopted_version>}, and that resolver matches
 * the CONSUMER's own release requests on version. A dependency version there would resolve to
 * nothing, or worse to somebody else's release that happened to share a number.
 *
 * <p>It also has to be readable BEFORE the child train exists. {@code child_train_id} is null for as
 * long as the sibling consumer has not spawned the adopting release's station (see the race below),
 * and the link above has to compose in that window too — so the version cannot be something only
 * reachable through the child.
 *
 * <p>The dependency version actually taken is not lost, merely not duplicated: it is the component
 * row in {@code mt_artifact_component} that this evaluation matched, reachable from the consumer's
 * artifact at {@code adopted_version}. No column is added for it because nothing reads one.
 *
 * <h2>The three ways in, which are one evaluation</h2>
 *
 * <ul>
 *   <li>{@link #ingested} — an SBOM landed. The consumer is the artifact's repository and the
 *       evidence is that one document.
 *   <li>{@link #arrived} — a train was spawned. The evidence is that release's own documents, and
 *       what it is really for is the OTHER SIDE OF A RACE (below).
 *   <li>{@link #recover} — a boot. Every owed node against the whole current graph.
 * </ul>
 *
 * <h2>THE RACE, AND WHY THE SPAWN HAS TO EVALUATE TOO</h2>
 *
 * <p>{@code mt_artifact} rows and {@code mt_train} rows are written by two INDEPENDENT durable
 * consumers of the same {@code SoftwareRelease} event, deliberately (see {@code
 * ReleaseTrainListener}) — and neither waits for the other. So when a frontend releases the version
 * that carries a library's release, the two facts arrive in whichever order the bus offers them:
 *
 * <ul>
 *   <li><b>SBOM first.</b> The ingest adopts the library train's node, looks for the train of
 *       {@code (frontend, that version)} to link it to, and finds nothing. The link is left null.
 *   <li><b>Spawn first.</b> The train of {@code (frontend, that version)} exists and there is no
 *       ingested document yet to adopt anything with.
 * </ul>
 *
 * <p>Either way the missing side turns up second, which is why BOTH sides evaluate: the ingest
 * resolves the link if the train is there, and the spawn resolves it if the adoption is there.
 * Neither ordering leaves a node stranded, and nothing has to be scheduled to notice.
 *
 * <h2>Monotonicity, which is the whole correctness argument</h2>
 *
 * <p>A train is a LOG. Nothing here ever moves a node backwards: an ADOPTED node is never returned
 * to PENDING, a LANDED node is never touched again, and re-evaluating an ADOPTED node with the same
 * or older evidence writes nothing at all. The only thing a second evaluation of an ADOPTED node can
 * do is fill in a {@code child_train_id} that was null — one write, once, {@code
 * MaintenanceStore.linkNodeChild} refuses the rest. That is what makes every entry point above safe
 * to call twice, in any order, from a redelivery or from a boot.
 */
@ApplicationScoped
public class TrainEvaluator {

  private static final Logger LOG = Logger.getLogger(TrainEvaluator.class);

  /**
   * How many trains one cascade will walk before it gives up and says so.
   *
   * <p>The visited set below already makes a cycle terminate; this is the belt for the shape the
   * visited set cannot see — an estate whose dependency graph is genuinely enormous, or a bug that
   * keeps minting train ids. A cascade that hits it has walked more trains than this platform has
   * repositories many times over, and stopping with a WARN is better than a boot thread that never
   * returns.
   */
  private static final int CASCADE_LIMIT = 10_000;

  @Inject MaintenanceStore store;

  @Inject ReleaseCoordinates coordinates;

  @Inject WorkQueue queue;

  // --- the ways in ------------------------------------------------------------------------------

  /**
   * <b>THE SBOM HOOK.</b> One artifact's bill of materials has just been stored; read it as
   * evidence that the repository which released it took whatever it was owed.
   *
   * <p>Called from {@code SbomIngestService.ingest} after {@code replaceGraph} committed, on the
   * worker thread. The artifact's {@code occurred_at} is what stamps the adoption, never the clock:
   * a catch-up that ingests four months of releases in a minute would otherwise date every adoption
   * today.
   */
  public void ingested(UUID artifactId) {
    Optional<MtArtifact> found = store.artifact(artifactId);
    if (found.isEmpty()) {
      return;
    }
    MtArtifact artifact = found.get();
    String consumer = store.repositoryName(artifact.repository);
    if (consumer == null || consumer.isBlank()) {
      // An artifact row whose release named no repository. There is no consumer to look nodes up
      // by, and inventing one from the coordinate would be a guess.
      return;
    }
    List<MaintenanceStore.NodeOnTrain> owed = store.owedNodes(consumer);
    if (owed.isEmpty()) {
      return;
    }
    apply(
        consumer,
        List.of(
            new Evidence(artifact.version, artifact.occurredAt, store.components(artifact.id))),
        owed);
  }

  /**
   * <b>THE SPAWN HOOK — the other side of the race.</b> A train has just been opened for
   * {@code (repository, version)}; find the nodes elsewhere in the estate whose adoption was that
   * very release and could not be linked because this train did not exist yet.
   *
   * <p>It re-reads the evidence rather than trusting a version key, and that is deliberate: the
   * question "is this node's adoption the one THIS release carries" is answered by the same
   * component match that made the adoption in the first place, so the link cannot land on a
   * release that happens to share a number with something else.
   *
   * <p>It also adopts, when the ingest happens to have run first for a node still PENDING. Same
   * evaluation, so there is nothing extra to keep in step.
   *
   * <p><b>Cheap when there is nothing to do</b>, which is the ordinary case: a library that nothing
   * else's train is waiting on costs two indexed reads and returns. That matters because the caller
   * is {@link TrainService#spawn}, which runs inside the bus's claim transaction.
   */
  public void arrived(MtTrain train) {
    if (train == null || train.repository == null) {
      return;
    }
    List<MaintenanceStore.NodeOnTrain> owed = store.owedNodes(train.repository);
    if (owed.isEmpty()) {
      // …and the degenerate arrival still has to be able to land somebody: a train COMPLETED at
      // creation whose referrers are already linked is landed by the cascade below rather than by
      // an evaluation, because there is no node of this repository left to evaluate.
      cascadeIfArrived(train);
      return;
    }
    apply(train.repository, evidenceOf(train.repository, train.version), owed);
    cascadeIfArrived(train);
  }

  /**
   * <b>THE BOOT STEP.</b> Re-evaluates every owed node against the graph as it stands now, and
   * closes every train whose nodes all landed while nothing was there to notice.
   *
   * <p>It heals two things and neither of them is hypothetical. A process that died between {@code
   * replaceGraph} committing and the hook behind it running has a graph that says a version was
   * adopted and a node that says it was not; and the day the derivation of adoption improves, this
   * is the backfill path for every train that is still open — no migration, no admin route.
   *
   * <p><b>Queued, never run here.</b> Like every other step in {@code work/RestartRecovery}, it
   * puts one task on the single worker thread and returns: a boot must not wait for a walk of the
   * estate, and the work belongs behind the SBOM re-queues that run in front of it anyway.
   */
  public void recover() {
    queue.submit("re-evaluate the release trains", this::reevaluate);
  }

  /** The body of {@link #recover}, on the worker thread. Idempotent, and safe to call by hand. */
  void reevaluate() {
    List<MaintenanceStore.NodeOnTrain> owed = store.owedNodes();
    Map<String, List<MaintenanceStore.NodeOnTrain>> byConsumer = new LinkedHashMap<>();
    for (MaintenanceStore.NodeOnTrain node : owed) {
      byConsumer.computeIfAbsent(node.node().consumer, key -> new ArrayList<>()).add(node);
    }
    for (Map.Entry<String, List<MaintenanceStore.NodeOnTrain>> entry : byConsumer.entrySet()) {
      try {
        // One consumer's whole graph read once and matched against every train it owes, rather than
        // a read per node: a repository on twenty trains is one artifact listing and one components
        // read per released package.
        apply(entry.getKey(), evidenceOf(entry.getKey(), null), entry.getValue());
      } catch (RuntimeException e) {
        // One consumer's evaluation costs that consumer's nodes and nothing else. A boot-time walk
        // that gave up on the first surprise would leave the rest of the estate unhealed.
        LOG.errorf(e, "The release-train nodes owed by %s could not be re-evaluated", entry.getKey());
      }
    }
    // THE LANDINGS THE LOOP ABOVE CANNOT MAKE. An ADOPTED node that already carries a child train is
    // skipped by every evaluation — there is nothing left to adopt or to link — so if that child
    // arrived while nothing was there to cascade, the node sits ADOPTED for ever. Re-running the
    // cascade from each linked child is what closes that, and it costs one read per child to find
    // out that most of them have not arrived.
    Set<UUID> children = new LinkedHashSet<>();
    for (MaintenanceStore.NodeOnTrain node : owed) {
      if (node.node().childTrainId != null) {
        children.add(node.node().childTrainId);
      }
    }
    for (UUID child : children) {
      cascade(child);
    }

    // AND THE COMPLETION BELT, which is a different repair. The loops above move nodes; this one
    // closes a train whose last node landed in a process that died before the completion was
    // written — a state no evaluation above would ever revisit, because it has no owed node left.
    int closed = 0;
    for (MtTrain train : store.unfinishedTrains()) {
      if (completeIfArrived(train.id)) {
        closed++;
        cascade(train.id);
      }
    }
    if (!byConsumer.isEmpty() || closed > 0) {
      LOG.infof(
          "Re-evaluated %d owed release-train node(s) across %d consumer(s); %d train(s) were"
              + " already complete and are now closed.",
          owed.size(), byConsumer.size(), closed);
    }
  }

  // --- the evaluation ---------------------------------------------------------------------------

  /**
   * What one release of one repository contains, as the evidence an adoption is read out of.
   *
   * @param version the CONSUMER's own released version — what its child train is keyed by
   * @param occurredAt the publisher's moment, which becomes {@code adopted_at}
   * @param components everything that release's bill of materials listed
   */
  private record Evidence(String version, Instant occurredAt, List<MtArtifactComponent> components) {}

  /**
   * A component that closes a node, reported as the RELEASE it was found in.
   *
   * <p>The component's own version is not carried out of {@link #match}: it is what proved the
   * adoption and nothing reads it afterwards. What both writes below need is the consumer's
   * release — its version for {@code adopted_version} and its moment for {@code adopted_at}, and
   * the same version again to find the child train.
   *
   * @param releaseVersion the CONSUMER's released version, which is what {@code adopted_version}
   *     holds — see the class comment for why it is not the dependency version
   * @param occurredAt that release's publishing moment
   */
  private record Match(String releaseVersion, Instant occurredAt) {}

  /**
   * One consumer's evidence against every node that consumer owes.
   *
   * <p>The three states of a node are three different questions, and only two of them are asked
   * here: a PENDING node asks "did they take it", an ADOPTED node with no child train asks "which
   * release of theirs was that", and a LANDED node asks nothing ever again.
   */
  private void apply(
      String consumer, List<Evidence> evidence, List<MaintenanceStore.NodeOnTrain> owed) {
    if (evidence.isEmpty() || owed.isEmpty()) {
      return;
    }
    Map<UUID, Set<Coordinate>> coordinatesOfTrain = new HashMap<>();
    Map<String, Optional<MtTrain>> childTrains = new HashMap<>();
    Set<UUID> arrivedChildren = new LinkedHashSet<>();
    int adopted = 0;
    int linked = 0;

    for (MaintenanceStore.NodeOnTrain owedNode : owed) {
      MtTrainNode node = owedNode.node();
      MtTrain train = owedNode.train();
      TrainNodeState state = TrainNodeState.of(node.state);
      if (state == TrainNodeState.LANDED) {
        continue;
      }
      if (state == TrainNodeState.ADOPTED && node.childTrainId != null) {
        // Adopted and already linked: there is nothing a second evaluation could add, and rewriting
        // the adoption from newer evidence would move a logged fact.
        continue;
      }
      Set<Coordinate> released =
          coordinatesOfTrain.computeIfAbsent(
              train.id, id -> coordinates.of(train.repository, train.version));
      if (released.isEmpty()) {
        // A train whose release published nothing this service can join on — a docs-only release,
        // or one whose artifact rows never arrived. There is no coordinate to look for.
        continue;
      }
      Match match = match(evidence, released, train.version);
      if (match == null) {
        continue;
      }
      MtTrain child =
          childTrains
              .computeIfAbsent(match.releaseVersion(), version -> store.train(consumer, version))
              .orElse(null);
      UUID childId = child == null ? null : child.id;
      if (state == TrainNodeState.PENDING) {
        // The CONSUMER's released version, not the component version that proved it. See the class
        // comment: this column is read as one half of the adopting release's request link.
        store.nodeAdopted(node.id, match.releaseVersion(), childId, match.occurredAt());
        adopted++;
      } else if (childId != null && store.linkNodeChild(node.id, childId)) {
        linked++;
      } else {
        continue;
      }
      if (child != null && TrainStatus.of(child.status) == TrainStatus.COMPLETED) {
        // The adoption's own release has already arrived everywhere, so this node is not merely
        // adopted — it has landed. The cascade below is what writes that.
        arrivedChildren.add(child.id);
      }
    }

    if (adopted > 0 || linked > 0) {
      LOG.infof(
          "%s adopted %d release-train node(s) and linked %d to a release of its own",
          consumer, adopted, linked);
    }
    for (UUID child : arrivedChildren) {
      cascade(child);
    }
  }

  /**
   * The first component of any of these releases that closes a node on a train at this version.
   *
   * <p><b>Inclusive</b>: equal counts, and higher counts. See the class comment.
   */
  private static Match match(
      List<Evidence> evidence, Set<Coordinate> released, String trainVersion) {
    for (Evidence release : evidence) {
      for (MtArtifactComponent component : release.components()) {
        Ecosystem ecosystem = Ecosystem.of(component.ecosystem).orElse(null);
        if (ecosystem == null || component.name == null) {
          // A purl type this service does not map. Stored, shown, never matched.
          continue;
        }
        if (!released.contains(new Coordinate(ecosystem, component.name))) {
          continue;
        }
        if (component.version == null || component.version.isBlank() || trainVersion == null) {
          continue;
        }
        if (VersionOrder.comparator(ecosystem).compare(component.version, trainVersion) < 0) {
          continue;
        }
        return new Match(release.version(), release.occurredAt());
      }
    }
    return null;
  }

  /**
   * What one repository has released, as far as the graph knows it.
   *
   * @param version one release of it, or null for the newest of every package it publishes — which
   *     is what a boot-time re-evaluation wants, because the question there is "does what they ship
   *     now carry it" rather than "did that one release"
   */
  private List<Evidence> evidenceOf(String repository, String version) {
    List<Evidence> evidence = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    // Ordered (ecosystem, name, occurredAt desc) by the store, so the first row of each package is
    // its newest release.
    for (MtArtifact artifact : store.artifactsOfRepository(coordinates.spellings(repository))) {
      if (version != null && !version.equals(artifact.version)) {
        continue;
      }
      if (SbomStatus.of(artifact.sbomStatus) != SbomStatus.INGESTED) {
        // A row that is PENDING, MISSING or FAILED holds no components, so it is not evidence of
        // anything — including of a NON-adoption. A train stays owed rather than being answered.
        continue;
      }
      if (version == null && !seen.add(artifact.ecosystem + " " + artifact.name)) {
        continue;
      }
      evidence.add(
          new Evidence(artifact.version, artifact.occurredAt, store.components(artifact.id)));
    }
    return evidence;
  }

  // --- the cascade ------------------------------------------------------------------------------

  /**
   * <b>ONE TRAIN ARRIVED, SO WORK OUT WHO ELSE JUST DID.</b>
   *
   * <p>A node pointing at a completed train has landed: the consumer took the version, released it,
   * and that release has now reached everybody it was owed to. Landing it may be the last node its
   * own train was waiting for, which completes that train, which lands ITS referrers — and the
   * estate is deep enough (a library, a component library, a frontend, a service, the wrapper) that
   * this runs several levels most days.
   *
   * <p><b>A WORKLIST, NOT RECURSION, AND A VISITED SET.</b> The depth is a property of somebody
   * else's dependency graph, not of this code, so the stack is the wrong place to keep it. And the
   * graph is not guaranteed acyclic: two repositories that end up depending on each other's
   * releases must degrade to "neither train ever completes", which is the honest answer, rather
   * than to a stack overflow or a loop that never ends. The visited set is what makes a cycle a
   * finite walk; the honest answer falls out of the completion rule itself, because a train in a
   * cycle never has all its nodes landed in the first place.
   */
  private void cascade(UUID completedTrainId) {
    Deque<UUID> worklist = new ArrayDeque<>();
    Set<UUID> visited = new LinkedHashSet<>();
    worklist.addLast(completedTrainId);
    while (!worklist.isEmpty()) {
      UUID trainId = worklist.removeFirst();
      if (!visited.add(trainId)) {
        // Already walked. A cycle in the estate reaches here and stops, which is the whole guard.
        continue;
      }
      if (visited.size() > CASCADE_LIMIT) {
        LOG.warnf(
            "The completion cascade from train %s walked %d trains and was stopped; the next boot's"
                + " re-evaluation will pick up whatever is left.",
            completedTrainId, CASCADE_LIMIT);
        return;
      }
      MtTrain arrived = store.train(trainId).orElse(null);
      if (arrived == null || TrainStatus.of(arrived.status) != TrainStatus.COMPLETED) {
        continue;
      }
      // The child's OWN arrival is when the parent landed. A real observation rather than the
      // clock, and the same instant for every referrer, which is what makes a replay idempotent.
      Instant landedAt = arrived.completedAt == null ? arrived.createdAt : arrived.completedAt;
      Set<UUID> parents = new LinkedHashSet<>();
      for (MtTrainNode node : store.nodesAwaitingChild(trainId)) {
        store.nodeLanded(node.id, null, null, landedAt);
        parents.add(node.trainId);
      }
      for (UUID parent : parents) {
        if (completeIfArrived(parent)) {
          worklist.addLast(parent);
        }
      }
    }
  }

  /** {@link #cascade} from a train that has already arrived, and nothing at all otherwise. */
  private void cascadeIfArrived(MtTrain train) {
    if (train != null && TrainStatus.of(train.status) == TrainStatus.COMPLETED) {
      cascade(train.id);
    }
  }

  /**
   * <b>THE COMPLETION RULE: every node LANDED, and the moment is the LAST of those landings.</b>
   *
   * <p>The timestamp is a real observation rather than the clock — the arrival of whichever child
   * train closed the last node — so a replay of the same landings writes the same instant, and a
   * catch-up does not date a journey that finished in August as having finished today. (Unlike
   * {@code created_at}, which is off the frame because supersession is decided on it, this one is
   * merely honest: nothing is ordered by it.)
   *
   * @return whether this call is the one that closed the train
   */
  private boolean completeIfArrived(UUID trainId) {
    MtTrain train = store.train(trainId).orElse(null);
    if (train == null || TrainStatus.of(train.status) == TrainStatus.COMPLETED) {
      return false;
    }
    Instant latest = null;
    for (MtTrainNode node : store.trainNodes(trainId)) {
      if (TrainNodeState.of(node.state) != TrainNodeState.LANDED) {
        return false;
      }
      if (node.landedAt != null && (latest == null || node.landedAt.isAfter(latest))) {
        latest = node.landedAt;
      }
    }
    // A train with no nodes at all arrived the instant it left — the same reading `spawnTrain`
    // gives a degenerate station, applied here to one that lost its nodes some other way.
    store.completeTrain(trainId, latest == null ? train.createdAt : latest);
    LOG.infof("Release train %s of %s %s has arrived", trainId, train.repository, train.version);
    return true;
  }
}
