package eu.wohlben.qits.maintenance.train;

import eu.wohlben.qits.maintenance.entity.MtTrain;
import eu.wohlben.qits.maintenance.entity.MtTrainNode;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.TrainEndKind;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.train.ReleaseCoordinates.Coordinate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>THE TWO ENDS OF A RELEASE TRAIN THAT NO EVENT COVERS, asked about on a timer.</b>
 *
 * <p>Everything else a train decides arrives: a release publishes a frame, an SBOM ingest stores a
 * bill of materials, and {@link TrainEvaluator} reads the evidence that was pushed at it. Two ends
 * are not like that, and no amount of listening will make them so:
 *
 * <ul>
 *   <li><b>{@code CONFIG_IMAGE_PIN}</b> — an IMAGE release is adopted when the deployment
 *       configuration that runs it names the new version. That line lives in qits-configuration and
 *       moving it announces nothing.
 *   <li><b>{@code DAEMON_PIN}</b> — a daemon release is adopted when the service handing it out
 *       hands out the new build. qits-ci's adoption ladder decides that, on evidence of its own,
 *       and publishes no event when a rung is proven.
 * </ul>
 *
 * <p>So both are POLLED, and this is the poll. {@code schedule/TrainSweepSchedule} is the clock in
 * front of it and holds no logic at all, which is what makes every rule below testable without one.
 *
 * <h2>It also MATERIALISES the nodes a spawn could not place</h2>
 *
 * <p>{@link TrainService#spawn} runs inside the bus's claim transaction, where an outbound call
 * turns a slow peer into an event redelivered for ever — so an IMAGE repository's train is opened
 * with no config-pin nodes at all and the first sweep after it places them, through {@link
 * TrainService#recordConfigImagePins}. The consuming applications are whoever qits-configuration
 * says pins the released image; a train whose image nothing deploys legitimately gains nothing.
 *
 * <h2>Zero HTTP on an idle estate</h2>
 *
 * <p>The ordinary state of this platform is that nothing is owed: no polled node is outstanding and
 * no image train is awaiting materialisation. That state costs two indexed reads and a return, with
 * no call to either peer — which is what makes a ten-minute cron reasonable in the first place. Each
 * peer is asked only when there is something its answer could decide.
 *
 * <h2>What a landing means here, and what an unreachable peer means</h2>
 *
 * <p><b>At or above the train's version lands the node</b>, the same inclusive comparison the SBOM
 * side makes: a configuration that skipped straight past the released version to the one after has
 * still adopted it, and the node this closes is still that train's. Below it is the estate genuinely
 * still running the old copy, which is exactly what a train exists to show.
 *
 * <p><b>An unreachable peer leaves every one of its nodes PENDING and costs ONE warning.</b> Not one
 * per node: a qits-configuration that is down owes fifty log lines to nobody, and the sentence is the
 * same fifty times. Nothing is written, so the next sweep decides on a whole answer or on none.
 *
 * <p><b>Monotonic, like the rest of the train machinery.</b> A landed node is never revisited, a
 * placed node is never placed twice, and a sweep over an unchanged answer writes nothing — which is
 * what lets this run on a timer rather than on a trigger.
 *
 * <h2>Which thread this runs on</h2>
 *
 * <p>The scheduler's, not {@code work/WorkQueue}'s, and deliberately: the queue is the SBOM ingest's
 * single writer, and parking it on an HTTP read to qits-configuration would stall document ingestion
 * behind a peer that has nothing to do with it. That is not a new concurrency story either — the
 * evaluator already writes these tables from the bus listener threads and from the queue at the same
 * time, and every store method it uses is a short transaction that only ever moves a node forwards.
 */
@ApplicationScoped
public class TrainSweep {

  private static final Logger LOG = Logger.getLogger(TrainSweep.class);

  /**
   * How versions are ranked here.
   *
   * <p>DOCKER, which is maven's order under the covers — both a released image tag and a daemon
   * build are this platform's calver, and {@code ComparableVersion} is what ranks one release of it
   * against another everywhere else in this service.
   */
  private static final Comparator<String> ORDER = VersionOrder.comparator(Ecosystem.DOCKER);

  @Inject MaintenanceStore store;

  @Inject TrainService trains;

  @Inject TrainEvaluator evaluator;

  @Inject ReleaseCoordinates coordinates;

  @Inject ConfigPinsClient configPins;

  @Inject DaemonPinClient daemonPins;

  /**
   * What one sweep did.
   *
   * @param placed config-pin nodes materialised on trains that had none
   * @param landed nodes this pass closed
   * @param asked whether either peer was called at all — false is the ordinary, idle answer
   */
  public record Result(int placed, int landed, boolean asked) {}

  /** One pass. Safe to call by hand, safe to call twice, and a no-op whenever nothing is owed. */
  public Result sweep() {
    List<MtTrain> candidates = trains.configPinCandidates();
    List<MaintenanceStore.NodeOnTrain> owed = polledNodes();
    if (candidates.isEmpty() && owed.isEmpty()) {
      // THE IDLE PATH. Nothing to materialise and nothing to decide, so neither peer is touched.
      return new Result(0, 0, false);
    }

    int placed = 0;
    int landed = 0;
    boolean asked = false;

    List<MaintenanceStore.NodeOnTrain> configNodes =
        nodesOfKind(owed, TrainEndKind.CONFIG_IMAGE_PIN);
    if (!candidates.isEmpty() || !configNodes.isEmpty()) {
      asked = true;
      ConfigPinsClient.Result answer = configPins.read();
      if (!answer.ok()) {
        // ONE line for the whole sweep, whatever it cost. See the class comment.
        LOG.warnf(
            "The image pins could not be read, so %d config-pin node(s) stay pending and %d image"
                + " train(s) are not materialised: %s",
            configNodes.size(), candidates.size(), answer.error());
      } else {
        placed = materialise(candidates, answer);
        if (placed > 0) {
          // The nodes just placed may already be satisfied by the very answer that placed them — an
          // image released an hour ago and deployed since. Re-reading here lets them land in this
          // same pass rather than waiting ten minutes for a sweep that learns nothing new.
          owed = polledNodes();
        }
        landed += landConfigPins(nodesOfKind(owed, TrainEndKind.CONFIG_IMAGE_PIN), answer);
      }
    }

    List<MaintenanceStore.NodeOnTrain> daemonNodes = nodesOfKind(owed, TrainEndKind.DAEMON_PIN);
    if (!daemonNodes.isEmpty()) {
      asked = true;
      DaemonPinClient.Result answer = daemonPins.read();
      if (!answer.ok()) {
        LOG.warnf(
            "The daemon pin could not be read, so %d daemon-pin node(s) stay pending: %s",
            daemonNodes.size(), answer.error());
      } else {
        landed += landDaemonPins(daemonNodes, answer);
      }
    }

    if (placed > 0 || landed > 0) {
      LOG.infof(
          "The config-pin sweep placed %d node(s) and landed %d.", placed, landed);
    }
    return new Result(placed, landed, asked);
  }

  // --- the config-pin half ------------------------------------------------------------------------

  /**
   * Places a {@code CONFIG_IMAGE_PIN} node for every application the answer says deploys one of the
   * images a candidate train released.
   *
   * <p>A train whose release put no image in a registry — no docker {@code mt_artifact} row, because
   * the sibling consumer has not written one yet or because the release published nothing joinable —
   * is skipped rather than guessed at. The next sweep asks again.
   */
  private int materialise(List<MtTrain> candidates, ConfigPinsClient.Result answer) {
    int placed = 0;
    for (MtTrain train : candidates) {
      Set<String> images = releasedImages(train);
      if (images.isEmpty()) {
        LOG.debugf(
            "Train %s of %s %s released no image this service can name yet; nothing to materialise",
            train.id, train.repository, train.version);
        continue;
      }
      Set<String> applications = new LinkedHashSet<>();
      for (ConfigPinsClient.Pin pin : answer.pins()) {
        if (images.contains(pin.image())) {
          applications.add(pin.application());
        }
      }
      placed += trains.recordConfigImagePins(train.id, applications);
    }
    return placed;
  }

  /**
   * Lands every config-pin node whose application is observed deploying the version.
   *
   * <p><b>ONE APPLICATION CAN PIN ONE IMAGE THROUGH SEVERAL KEYS, and the LOWEST of them decides.</b>
   * A node is keyed by the application, not by the configuration entry, so an application holding
   * {@code qits/workspace} at the new version under one key and at the old one under another has not
   * finished taking the release — something it deploys still runs the old image. Taking the highest
   * would close the train on a half-moved configuration, which is the one thing the row is read to
   * rule out.
   */
  private int landConfigPins(
      List<MaintenanceStore.NodeOnTrain> nodes, ConfigPinsClient.Result answer) {
    Instant observedAt = answer.generatedAt() == null ? Instant.now() : answer.generatedAt();
    Map<UUID, Set<String>> imagesOfTrain = new HashMap<>();
    int landed = 0;
    for (MaintenanceStore.NodeOnTrain owed : nodes) {
      MtTrain train = owed.train();
      MtTrainNode node = owed.node();
      Set<String> images =
          imagesOfTrain.computeIfAbsent(train.id, id -> releasedImages(train));
      if (images.isEmpty()) {
        continue;
      }
      String lowest = null;
      for (ConfigPinsClient.Pin pin : answer.pins()) {
        if (!images.contains(pin.image()) || !pin.application().equals(node.consumer)) {
          continue;
        }
        if (lowest == null || ORDER.compare(pin.version(), lowest) < 0) {
          lowest = pin.version();
        }
      }
      if (lowest == null) {
        // The application no longer pins the image at all. The node stays owed: a train is a log of
        // what was expected, and a configuration that dropped the pin did not adopt the release.
        continue;
      }
      if (atLeast(lowest, train.version) && evaluator.observed(node.id, lowest, observedAt)) {
        landed++;
      }
    }
    return landed;
  }

  /** The unqualified image names one train's release put into a registry. */
  private Set<String> releasedImages(MtTrain train) {
    Set<String> images = new LinkedHashSet<>();
    for (Coordinate coordinate : coordinates.of(train.repository, train.version)) {
      if (coordinate.ecosystem() == Ecosystem.DOCKER && coordinate.name() != null) {
        images.add(coordinate.name());
      }
    }
    return images;
  }

  // --- the daemon half ----------------------------------------------------------------------------

  /**
   * Lands every daemon-pin node whose train is the daemon qits-ci answered about.
   *
   * <p><b>The answer names its own daemon, and that is what is matched on.</b> There is one qits-ci
   * address and one ladder behind it; a node whose train released some OTHER daemon cannot be
   * decided by this answer, and hardcoding which daemon lives behind {@code PeerTarget.CI} would be
   * the same fact written down in a second place. See {@code TrainService.DAEMON_ADOPTERS} for the
   * first.
   */
  private int landDaemonPins(
      List<MaintenanceStore.NodeOnTrain> nodes, DaemonPinClient.Result answer) {
    Instant observedAt = Instant.now();
    int landed = 0;
    for (MaintenanceStore.NodeOnTrain owed : nodes) {
      MtTrain train = owed.train();
      if (!train.repository.equals(answer.daemon())) {
        LOG.debugf(
            "The ladder at qits-ci answers for %s, not for %s; train %s is left owed",
            answer.daemon(), train.repository, train.id);
        continue;
      }
      // source is READ AND NOT GATED ON — adopted, configured and none are all qits-ci saying what
      // it hands out, and a node lands on the version. A `none` answer carries no version at all and
      // therefore lands nobody, which is the whole of the difference it makes.
      if (atLeast(answer.version(), train.version)
          && evaluator.observed(owed.node().id, answer.version(), observedAt)) {
        landed++;
      }
    }
    return landed;
  }

  // --- the shared bits ----------------------------------------------------------------------------

  /**
   * Every owed node whose evidence is polled rather than pushed.
   *
   * <p>Read from {@code owedNodes()}, so SUPERSEDED trains are in for the same reason the evaluation
   * keeps them: a configuration that moves to a version a newer release has already overtaken did
   * take that version, and the row should say so. Only COMPLETED is out, and a completed train has
   * no node left to move.
   */
  private List<MaintenanceStore.NodeOnTrain> polledNodes() {
    List<MaintenanceStore.NodeOnTrain> polled = new ArrayList<>();
    for (MaintenanceStore.NodeOnTrain owed : store.owedNodes()) {
      TrainEndKind kind = TrainEndKind.of(owed.node().endKind);
      if (kind == TrainEndKind.CONFIG_IMAGE_PIN || kind == TrainEndKind.DAEMON_PIN) {
        polled.add(owed);
      }
    }
    return polled;
  }

  private static List<MaintenanceStore.NodeOnTrain> nodesOfKind(
      List<MaintenanceStore.NodeOnTrain> nodes, TrainEndKind kind) {
    return nodes.stream()
        .filter(owed -> TrainEndKind.of(owed.node().endKind) == kind)
        .toList();
  }

  /** Whether an observed version is at or above the one a train carries. Blank is never either. */
  private static boolean atLeast(String observed, String wanted) {
    if (observed == null || observed.isBlank() || wanted == null || wanted.isBlank()) {
      return false;
    }
    return ORDER.compare(observed, wanted) >= 0;
  }
}
