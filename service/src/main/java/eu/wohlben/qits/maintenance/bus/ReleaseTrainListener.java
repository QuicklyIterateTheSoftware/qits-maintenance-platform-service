package eu.wohlben.qits.maintenance.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.train.TrainService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * <b>Every release gets a station.</b> Consumes qits-ci's {@code SoftwareRelease} and opens (or
 * settles onto) the release train of that {@code (repository, version)} — see {@link TrainService}
 * for what a train is and why its membership is frozen at the release.
 *
 * <h2>Why a SECOND consumer of the same event</h2>
 *
 * <p>{@link SoftwareReleaseListener} already reads {@code SoftwareRelease}, and the instinct is to
 * add a line to it rather than a class beside it. That would be wrong on the axis that matters here:
 * <b>a durable consumer is a WATERMARK, and two independent pieces of work behind one watermark
 * cannot fail independently.</b> A train spawn that threw would roll back the claim that also moves
 * {@code mt_latest} and opens the SBOM outbox row, so a bug in the newest feature on the platform
 * would stop the oldest one from recording releases at all — and the redelivery would re-run both.
 * Two consumer ids are two watermarks, two claim rows and two blast radii, at the cost of the bus
 * offering each frame twice.
 *
 * <p>It also lets this one initialize where it has to. {@code maintenance-release-trains} is a NEW
 * consumer: it starts at the head of the log and knows nothing about the releases that happened
 * before this deploy, which is exactly right — a train for a release from last month would be
 * derived from today's pins and would be a fiction. Folding this into the existing consumer would
 * have made the same code run over its entire catch-up history.
 *
 * <h2>What it acts on, which is MORE than its sibling does</h2>
 *
 * <p>{@link SoftwareReleaseListener} maps {@code packageType} onto the three ecosystems this service
 * inventories and settles everything else at DEBUG, because {@code daemon} and {@code docs} are
 * releases of things no manifest pins. <b>This listener acts on all five.</b> A train is not an
 * inventory row:
 *
 * <ul>
 *   <li><b>{@code daemon}</b> spawns a real journey. Nothing pins a daemon in a manifest — it is
 *       distributed by a service, which records the build it hands out — so the train of a DAEMON
 *       repository carries a {@code DAEMON_PIN} node and no manifest node at all.
 *   <li><b>{@code docs}</b> folds into the station its siblings opened. An api-docs bundle is one
 *       package of one release, and {@code (repository, version)} is the key: a docs frame arriving
 *       first opens the station and the maven frame behind it fills in the adopters, arriving second
 *       it settles onto what is already there. Either way it is not a train of its own.
 *   <li>and a {@code packageType} qits-ci adds after this was written reads exactly like {@code
 *       docs}: a coordinate this service cannot join on, and a release that still happened.
 * </ul>
 *
 * <p>So there is no type filter here, only a coordinate one: the three ecosystem types hand {@link
 * TrainService} a {@link TrainService.ReleasedPackage} to look adopters up by, and the others hand
 * it null.
 *
 * <h2>The payload record is shared with {@link SoftwareReleaseListener}, on purpose</h2>
 *
 * <p>It is one wire shape — qits-ci's {@code SoftwareRelease} — and a transcription of another
 * repository's record is exactly the kind of thing that must exist once. A second copy would be a
 * second thing to keep in step when qits-ci renames a field, and the failure of missing one is
 * silent: this listener would bind nulls and stop spawning trains with nothing in any log.
 * {@code ForeignEventContractTest} pins that transcription and {@code EventWireReflection} already
 * registers it for the native image, so sharing costs nothing and buys the single edit.
 *
 * <h2>{@code repository} is a ROW ID on the wire and a NAME in the row</h2>
 *
 * <p>The same translation {@link SoftwareReleaseListener} makes, for the same reason and through the
 * same {@code MaintenanceStore.repositoryName}: every read a train makes — who pins what this
 * repository released, the other trains of this repository, the UI's link — joins a catalog name. It
 * matters more here than there, because a uuid would not merely write an unjoinable column: it would
 * make the supersession key wrong, so the name-spelled trains and the uuid-spelled ones of ONE
 * repository would never supersede each other.
 *
 * <h2>Failure: what is retried and what is swallowed</h2>
 *
 * <p>The rule is copied from {@link SoftwareReleaseListener} deliberately, because it is the seam's
 * rule rather than that listener's.
 *
 * <p><b>Retryable, and left to throw:</b> anything the store raises out of its own database work.
 * The claim rolls back and the release is offered again.
 *
 * <p><b>Poison, and swallowed with a WARN:</b> a payload that will not parse, and one that names no
 * repository or no version. The same bytes fail identically on every later offer, and a throw would
 * hold this consumer's watermark behind one bad event for ever — the seam has no dead letter.
 */
@ApplicationScoped
public class ReleaseTrainListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ReleaseTrainListener.class);

  /**
   * This consumption's storage key: it names every {@code consumed_event} row and the {@code
   * consumer_watermark} this listener is caught up by.
   *
   * <p><b>NEW, and never to be changed again.</b> A new value is a brand-new consumer initializing
   * at the HEAD of the log, silently skipping every release in between, with the old watermark
   * orphaned. It names the consumption, not the class, and it survives a rename of either.
   */
  static final String CONSUMER_ID = "maintenance-release-trains";

  /** The one event name this listener wants — {@code SoftwareRelease}'s signature. */
  static final String SIGNATURE = SoftwareReleaseListener.SIGNATURE;

  @Inject MaintenanceStore store;

  @Inject TrainService trains;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /**
   * {@code selects} is left at its default: every {@code SoftwareRelease} is claimed, because every
   * one of them is a release and every release gets a station. Unlike its sibling there is not even
   * a package type to filter on.
   */
  @Override
  public void onFrame(EventFrame frame) {
    SoftwareReleaseListener.SoftwareReleasePayload release = decode(frame);
    if (release == null) {
      // Warned in decode. Returning settles it: the same bytes fail identically on every later
      // offer, and an event nothing can read must not hold the watermark.
      return;
    }
    String spelling = trimmed(release.repository());
    String version = trimmed(release.version());
    if (spelling == null || version == null) {
      LOG.warnf(
          "%s %s names no (repository, version) to open a release train for; it is skipped",
          frame.name(), frame.id());
      return;
    }
    // The store throws out of here on a database that will not answer, which is exactly right: the
    // claim rolls back and the next sweep offers this release again.
    String repository = store.repositoryName(spelling);

    TrainService.ReleasedPackage announced = announced(release);
    MaintenanceStore.TrainSpawn spawned =
        trains.spawn(repository, version, announced, occurredAt(frame));
    LOG.debugf(
        "%s %s folded %s %s into train %s (%s, %d node(s))",
        frame.name(),
        frame.id(),
        release.packageType(),
        release.packageName(),
        spawned.train().id,
        spawned.train().status,
        spawned.nodes());
  }

  /**
   * The coordinate this frame's package can be looked adopters up by, or null.
   *
   * <p>Null for {@code daemon}, for {@code docs} and for anything qits-ci adds later — <b>and null
   * is not a settle here</b>, which is the one place this listener parts company with its sibling. A
   * release with no joinable coordinate still opens its station; it simply contributes no manifest
   * adopters to it.
   */
  private static TrainService.ReleasedPackage announced(
      SoftwareReleaseListener.SoftwareReleasePayload release) {
    Ecosystem ecosystem = SoftwareReleaseListener.ECOSYSTEMS.get(packageType(release));
    String name = trimmed(release.packageName());
    if (ecosystem == null || name == null) {
      return null;
    }
    return new TrainService.ReleasedPackage(ecosystem, name);
  }

  /**
   * The publisher's moment, off the frame.
   *
   * <p><b>Never this service's clock, and here that is load-bearing rather than tidy:</b> {@code
   * mt_train.created_at} is the key supersession is decided on. A catch-up that replays four
   * releases of one repository in as many seconds would stamp them all "now" in ARRIVAL order, and
   * whichever happened to be handled last would supersede the other three regardless of which was
   * actually the newest release.
   */
  private static Instant occurredAt(EventFrame frame) {
    return frame.occurredAt() == null ? Instant.now() : frame.occurredAt();
  }

  /** The type as the wire spells it, lower-cased so a publisher's capitalisation cannot matter. */
  private static String packageType(SoftwareReleaseListener.SoftwareReleasePayload release) {
    String type = trimmed(release.packageType());
    return type == null ? "" : type.toLowerCase(Locale.ROOT);
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private static SoftwareReleaseListener.SoftwareReleasePayload decode(EventFrame frame) {
    try {
      return CanonicalJson.payloadTo(
          frame.payload(), SoftwareReleaseListener.SoftwareReleasePayload.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload: %s",
          frame.name(), frame.id(), unreadable.toString());
      return null;
    }
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
