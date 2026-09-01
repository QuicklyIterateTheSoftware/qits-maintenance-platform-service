package eu.wohlben.qits.maintenance.bus;

import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>What source control did to a repository this service tracks</b> — the three SCM facts that
 * change something here, and nothing else.
 *
 * <table>
 *   <caption>The three events and what each one moves</caption>
 *   <tr><th>event</th><th>publisher</th><th>what it means here</th></tr>
 *   <tr><td>{@code SCMRelease}</td><td>qits-workspaces</td>
 *       <td>a {@code maintenance/<group>} branch went through the release door — the branch row
 *           becomes RELEASED, which nothing else on the platform could ever tell this service</td></tr>
 *   <tr><td>{@code SCMDeleteBranch}</td><td>qits-githost</td>
 *       <td>a {@code maintenance/<group>} branch is gone — the branch row becomes NONE, so the next
 *           bump starts fresh from main</td></tr>
 *   <tr><td>{@code SCMPublishCommit}</td><td>qits-githost</td>
 *       <td>a push landed on a repository's OWN main branch, so its manifests are not what this
 *           inventory holds — one repository is queued for a rescan</td></tr>
 * </table>
 *
 * <h2>Why RELEASED finally gets written</h2>
 *
 * <p>{@code BranchState.RELEASED} has existed since the schema did and nothing wrote it. The reason
 * is that the release is somebody else's operation entirely: an operator opens the branch this
 * service pushed, releases it through qits-workspaces' door, and the door pushes a tag and deletes
 * the source branch. Polling could see the branch disappear, which is indistinguishable from a
 * person deleting it by hand; only the door knows a release happened, and only the event says so.
 *
 * <p>{@code SCMDeleteBranch} then arrives moments later and writes NONE over it. That is not a
 * conflict — a released branch IS gone, and NONE is exactly "the next bump starts fresh from main".
 * RELEASED is the fact recorded in between, and it is the state a branch a person deleted by hand
 * never passes through. The same delete is also what clears a STALE row: a branch somebody rewrote
 * is one this service stops writing to until it is gone, and this is how it learns that it is.
 *
 * <h2>Why a main-branch push is a scan and not a bump</h2>
 *
 * <p>A push changes a MANIFEST, so the honest answer to one is to re-read it — one repository, at
 * the head the push just made. Whether the pending set that falls out should become a branch is
 * still the clock's standing instruction or a person's press: {@link ScanTrigger#EVENT} scans and
 * never bumps, or every repository somebody touched during the day would grow a branch.
 *
 * <p>The scan goes through {@link ScanService#request} — the same path {@code POST /scans} with a
 * {@code repository} takes — so it is a row a client can follow, it is queued behind the one worker
 * thread, and it closes itself on failure like any other. Nothing here is a second scanning
 * mechanism.
 *
 * <p><b>It is debounced against the store, not against a field.</b> {@link
 * MaintenanceStore#scanPending} answers whether a scan of exactly this repository is already queued
 * or running; a merge is a burst of pushes, and without this a burst is five rows queued behind one
 * thread each re-reading a tree the one in front of it already read. A field would not survive a
 * restart and would not see the scan a person queued from the UI a second earlier.
 *
 * <h2>{@code selects} stays default, and the filtering is here</h2>
 *
 * <p>Every frame of the three signatures leaves a claim row, including the pushes to branches this
 * service has no opinion about — which on this platform is most of them. That is deliberate: the
 * seam asks a predicate to be PURE, and every question worth asking here is a database read (is this
 * repository in the catalog, is that its main branch, is that group one of its own). A predicate
 * that read the store would be asked once per frame and then asked again in {@link #onFrame}, and
 * one that threw on a database blip would leave the event owed for ever. The claim table is bounded
 * anyway — the sweeper prunes claims the watermark has passed by more than the library's
 * {@code prune-horizon}.
 *
 * <h2>The three payloads are TRANSCRIPTIONS, and that is a standing instruction</h2>
 *
 * <p>Neither publisher ships a vocabulary jar this repository could depend on — qits-workspaces
 * publishes none at all, and taking qits-githost-events would be a compile-time dependency on
 * another context for three field lists. So each record below transcribes only the fields this
 * listener consumes, decoded by {@link CanonicalJson} exactly as the publisher encoded them, and
 * {@code bus/ForeignEventContractTest} pins every name against the canonical form. <b>A rename over
 * there is a change to that transcription in the same campaign</b>; landing it there and not here
 * leaves this suite green and this listener silently deaf, which is the one failure the test cannot
 * prevent and is why it says so out loud.
 *
 * <h2>Failure</h2>
 *
 * <p>The seam's rule: a throw rolls the claim back and the event is owed for ever, so swallow what
 * retrying cannot fix and throw what it can. A payload that will not parse, one that names no
 * repository this service knows, and one naming a group that repository does not have are all
 * poison — the same bytes fail identically every time — so each is a WARN or a DEBUG and a return. A
 * database that could not answer is left to throw, because the next attempt is exactly what fixes
 * it.
 */
@ApplicationScoped
public class ScmEventListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ScmEventListener.class);

  /**
   * This consumption's storage key, in {@code consumed_event} and {@code consumer_watermark}.
   *
   * <p><b>Never change it.</b> A new value is a brand-new consumer initializing at the head of the
   * log, silently skipping everything in between. It names the consumption, not the class.
   */
  static final String CONSUMER_ID = "maintenance-branch-tracking";

  /** qits-workspaces' "this version is on the default branch, pushed and tagged". */
  static final String RELEASE_SIGNATURE = "SCMRelease";

  /** qits-githost's "this branch is gone, and here is the tip it last had". */
  static final String DELETE_SIGNATURE = "SCMDeleteBranch";

  /** qits-githost's "this branch moved", one per successfully updated ref of a push. */
  static final String PUSH_SIGNATURE = "SCMPublishCommit";

  /** Every branch this service writes is under it, and the group is what follows. */
  static final String BRANCH_PREFIX = "maintenance/";

  /**
   * The {@code SCMRelease} fields this listener consumes, transcribed from qits-workspaces'
   * {@code workspaces-events/…/SCMRelease.java}.
   *
   * <p>{@code repositoryName} is the coordinate this service is keyed by and {@code repository} is
   * the registry's row id, which for a repository the platform manifest declares happens to be the
   * same string — so the name is preferred and the id is the fallback, exactly the tolerance
   * qits-ci's release join carries and for the same reason. {@code projectId} is transcribed
   * because it is part of the shape and deliberately unused: this service resolves a project from
   * its own {@code mt_repository} row.
   */
  public record ScmReleasePayload(
      String projectId, String repository, String repositoryName, String branch, String version) {}

  /**
   * The {@code SCMDeleteBranch} fields this listener consumes, transcribed from qits-githost's
   * {@code githost-events/…/SCMDeleteBranch.java}.
   *
   * <p>{@code repoName} and {@code projectId} are the address the push arrived on, echoed rather
   * than resolved, and both are <b>null for a push on the internal {@code /git/<storageId>}
   * scheme</b> — a mirror sync. Such an event names no repository this service can address and is
   * settled. {@code sha} is the OLD tip and is transcribed for the shape; nothing here reads it,
   * because a deleted branch has no head to record.
   */
  public record ScmDeleteBranchPayload(
      String repoId, String projectId, String repoName, String branch, String sha) {}

  /**
   * The {@code SCMPublishCommit} fields this listener consumes, transcribed from qits-githost's
   * {@code githost-events/…/SCMPublishCommit.java}.
   *
   * <p>That record carries eleven more components — the head commit's parents, author, both
   * timestamps, the message and {@code suppressCi} — and none of them is here. Only what is
   * consumed is transcribed: the mapper ignores what it is not told about, which is what lets
   * qits-githost add a field without this becoming a poison payload. {@code sha} is kept because it
   * is what a log line needs to say WHICH push queued a scan; the scan itself resolves the head for
   * itself, once, exactly as every scan does.
   */
  public record ScmPublishCommitPayload(
      String repoId, String projectId, String repoName, String branch, String sha) {}

  @Inject MaintenanceStore store;

  @Inject ScanService scans;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(RELEASE_SIGNATURE, DELETE_SIGNATURE, PUSH_SIGNATURE);
  }

  @Override
  public void onFrame(EventFrame frame) {
    switch (frame.name()) {
      case RELEASE_SIGNATURE -> onRelease(frame);
      case DELETE_SIGNATURE -> onDelete(frame);
      case PUSH_SIGNATURE -> onPush(frame);
      default ->
          // Unreachable through the funnel, which offers only the signatures above. Settling is the
          // right answer anyway: an event this listener did not ask for is not one it can act on.
          LOG.debugf("%s %s is not a signature this listener acts on", frame.name(), frame.id());
    }
  }

  // --- SCMRelease -----------------------------------------------------------------------------

  /** A maintenance branch went through the release door. */
  private void onRelease(EventFrame frame) {
    ScmReleasePayload release = decode(frame, ScmReleasePayload.class);
    if (release == null) {
      return;
    }
    String branch = trimmed(release.branch());
    if (branch == null || !branch.startsWith(BRANCH_PREFIX)) {
      // The ordinary case by a long way: every release of every repository on the platform rides
      // this signature, and almost none of them is one of ours.
      LOG.debugf(
          "%s %s released the branch '%s', which is not a maintenance branch",
          frame.name(), frame.id(), release.branch());
      return;
    }
    String repository = trimmed(release.repositoryName());
    if (repository == null) {
      // The registry answered with no name. The id is the same string for a manifest repository,
      // which is what makes the fallback worth having rather than a refusal.
      repository = trimmed(release.repository());
    }
    String group = branch.substring(BRANCH_PREFIX.length());
    Optional<MtGroup> known = group(repository, group);
    if (known.isEmpty()) {
      LOG.warnf(
          "%s %s released %s of a repository or group this inventory does not hold (%s/%s);"
              + " it is settled",
          frame.name(), frame.id(), branch, release.repositoryName(), group);
      return;
    }
    // The head is kept rather than nulled: at this instant the branch has been released and not yet
    // deleted, so the sha this service last read is still the truest thing it knows about it. The
    // SCMDeleteBranch that follows is what clears it.
    String head = store.branch(repository, group).map(row -> row.headSha).orElse(null);
    store.recordBranch(repository, group, branch, BranchState.RELEASED, head, Instant.now());
    LOG.infof(
        "%s %s released %s of %s as %s; the branch row is RELEASED",
        frame.name(), frame.id(), branch, repository, release.version());
  }

  // --- SCMDeleteBranch ------------------------------------------------------------------------

  /** A maintenance branch is gone — released, or deleted by hand. */
  private void onDelete(EventFrame frame) {
    ScmDeleteBranchPayload deleted = decode(frame, ScmDeleteBranchPayload.class);
    if (deleted == null) {
      return;
    }
    String branch = trimmed(deleted.branch());
    if (branch == null || !branch.startsWith(BRANCH_PREFIX)) {
      LOG.debugf(
          "%s %s deleted the branch '%s', which is not a maintenance branch",
          frame.name(), frame.id(), deleted.branch());
      return;
    }
    String repository = trimmed(deleted.repoName());
    String group = branch.substring(BRANCH_PREFIX.length());
    Optional<MtGroup> known = group(repository, group);
    if (known.isEmpty()) {
      LOG.warnf(
          "%s %s deleted %s of a repository or group this inventory does not hold (%s/%s);"
              + " it is settled",
          frame.name(), frame.id(), branch, deleted.repoName(), group);
      return;
    }
    // NONE, and the head cleared with it: the next bump branches from main again. This is also the
    // only thing that ever clears a STALE row — a branch somebody rewrote is one this service stops
    // writing to until it is gone, and this is how it learns that it is.
    store.recordBranch(repository, group, branch, BranchState.NONE, null, Instant.now());
    LOG.infof(
        "%s %s deleted %s of %s; the branch row is NONE and the next bump starts from main",
        frame.name(), frame.id(), branch, repository);
  }

  // --- SCMPublishCommit -----------------------------------------------------------------------

  /** A push landed. If it was on the repository's own main branch, its manifests are re-read. */
  private void onPush(EventFrame frame) {
    ScmPublishCommitPayload push = decode(frame, ScmPublishCommitPayload.class);
    if (push == null) {
      return;
    }
    String repository = trimmed(push.repoName());
    String branch = trimmed(push.branch());
    if (repository == null || branch == null) {
      // An id-addressed push (a mirror sync) announces no name at all. Nothing to look up.
      LOG.debugf(
          "%s %s names no (repository, branch) this inventory can address", frame.name(),
          frame.id());
      return;
    }
    Optional<MtRepository> row = store.repository(repository);
    if (row.isEmpty()) {
      // A repository the catalog has, that no scan has read yet — or one this platform does not
      // track at all. Either way there is no row to refresh and no main branch to compare against;
      // the next scheduled scan reads the catalog and creates it.
      LOG.debugf(
          "%s %s pushed to %s, which this inventory does not hold yet", frame.name(), frame.id(),
          repository);
      return;
    }
    String mainBranch = trimmed(row.get().mainBranch);
    if (mainBranch == null || !mainBranch.equals(branch)) {
      // Every branch on the platform rides this signature, maintenance branches this service pushed
      // included. Only the branch a scan reads manifests at can invalidate the inventory.
      LOG.debugf(
          "%s %s pushed %s of %s, which is not its main branch (%s)",
          frame.name(), frame.id(), branch, repository, row.get().mainBranch);
      return;
    }
    if (store.scanPending(repository)) {
      // The debounce. A merge is a burst of pushes, and a scan already queued reads the head at the
      // moment it runs — which is at or after this push.
      LOG.debugf(
          "%s %s pushed %s of %s, and a scan of it is already queued or running",
          frame.name(), frame.id(), branch, repository);
      return;
    }
    // INTERNAL scope, not ALL: every scan re-reads every manifest whatever the scope says, and the
    // scope governs only which half of the registry lookups refresh. The internal half is one hop
    // away; asking Maven Central and npmjs about a push is the daily external scan's job.
    UUID id = scans.request(ScanScope.INTERNAL, repository, ScanTrigger.EVENT);
    LOG.infof(
        "%s %s pushed %s of %s at %s; queued the scan %s of that repository",
        frame.name(), frame.id(), branch, repository, push.sha(), id);
  }

  // --- shared ---------------------------------------------------------------------------------

  /**
   * The named group of the named repository, empty when either is unknown.
   *
   * <p>Both halves have to hold: a branch row is keyed on {@code (repository, group)} and writing
   * one for a group the repository does not have would put a row on a page nothing else ever
   * refreshes.
   */
  private Optional<MtGroup> group(String repository, String group) {
    if (repository == null || group == null || group.isBlank()) {
      return Optional.empty();
    }
    if (store.repository(repository).isEmpty()) {
      return Optional.empty();
    }
    return store.groups(repository).stream().filter(row -> group.equals(row.name)).findFirst();
  }

  /** Null on anything that will not read as this payload, warned about once, never thrown. */
  private static <P> P decode(EventFrame frame, Class<P> type) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), type);
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
