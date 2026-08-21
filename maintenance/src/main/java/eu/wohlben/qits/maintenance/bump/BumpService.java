package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.error.BumpDisabledException;
import eu.wohlben.qits.maintenance.error.NoSuchGroupException;
import eu.wohlben.qits.maintenance.error.NoSuchRepositoryException;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.pending.Change;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * A bump: what it asks qits-ci to change, and what it makes of the answer.
 *
 * <p><b>This service decides WHAT changes; a CI step applies them.</b> Nothing here clones,
 * rewrites a file or pushes a ref. The payload names a file, a location and two versions, and the
 * step that reads it is the only thing that touches the repository — which is what keeps this
 * service out of every repository's write path.
 *
 * <p><b>The branch head is read TWICE and that is the whole of "did it do anything".</b> Once
 * before the trigger, once when the run ends. A green run whose branch did not move means the step
 * found the versions already there: NOTHING_TO_DO, which is a real outcome and reads very
 * differently from SUCCEEDED in a list of nightly bumps.
 *
 * <p><b>Only the head is compared, never a commit count.</b> One bump is up to TWO commits — the
 * maven step and the node/docker step each clone, commit and push — so a bump that moved the branch
 * by two commits is the ordinary case and a service that expected one would report every mixed
 * group as broken.
 *
 * <p><b>The push is ff-only and never forced</b> — that rule lives in the pipeline, not here — so a
 * red run against a branch that MOVED is somebody's hand-written commit that this service refused
 * to overwrite. That is the STALE state: they own the branch now, and nothing bumps it again until
 * it is gone.
 */
@ApplicationScoped
public class BumpService {

  private static final Logger LOG = Logger.getLogger(BumpService.class);

  /** Every maintenance branch is under this prefix, which is also what the release door cleans. */
  public static final String BRANCH_PREFIX = "maintenance/";

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject CiClient ci;

  @Inject GitHostReader gitHost;

  @Inject WorkQueue queue;

  /**
   * Opens a bump and queues its dispatch.
   *
   * @throws NoSuchRepositoryException the inventory has no such repository — a 404
   * @throws NoSuchGroupException that repository declares no such group — a 404
   * @throws eu.wohlben.qits.maintenance.error.BumpAlreadyActiveException one is going — a 409
   * @throws BumpDisabledException {@code qits.maintenance.bump.enabled} is false — a 409
   */
  public UUID request(String repository, String group, BumpTrigger trigger) {
    if (!config.bumpEnabled()) {
      throw new BumpDisabledException();
    }
    MtRepository row =
        store.repository(repository).orElseThrow(() -> new NoSuchRepositoryException(repository));
    List<MtGroup> groups = store.groups(repository);
    if (groups.stream().noneMatch(candidate -> candidate.name.equals(group))) {
      throw new NoSuchGroupException(repository, group);
    }
    List<Change> changes =
        PendingChanges.forGroup(
            store.pins(repository), PendingChanges.index(store.allLatest()), groups, group);

    // THE CHANGES ARE FROZEN AT REQUEST TIME. A payload recomputed at dispatch would not be the one
    // the operator saw when they pressed the button, and a retry after a 503 would send a different
    // list than the first attempt under the same dedupe key.
    UUID id =
        store.openBump(
            repository,
            group,
            BRANCH_PREFIX + group,
            config.environment(),
            trigger,
            changes,
            Instant.now());
    queue.submit("bump " + id + " of " + repository + "/" + group, () -> dispatch(id));
    LOG.infof(
        "Opened the %s bump %s of %s/%s with %d changes",
        trigger, id, repository, group, changes.size());
    Objects.requireNonNull(row);
    return id;
  }

  /**
   * Sends one bump to qits-ci, or defers it.
   *
   * <p>Idempotent by design: a bump that is not REQUESTED any more has already been sent, and the
   * event id travelling with the payload means a second send that DID reach qits-ci records no
   * second run.
   */
  public void dispatch(UUID id) {
    Optional<MtBump> found = store.bump(id);
    if (found.isEmpty()) {
      return;
    }
    MtBump bump = found.get();
    if (!BumpStatus.REQUESTED.name().equals(bump.status)) {
      return;
    }
    List<Change> changes = changes(bump);
    if (changes.isEmpty()) {
      store.bumpFinished(id, BumpStatus.NOTHING_TO_DO, null, "nothing is pending in this group", Instant.now());
      return;
    }
    Optional<MtRepository> repository = store.repository(bump.repository);
    if (repository.isEmpty()) {
      store.bumpFinished(
          id, BumpStatus.FAILED, null, "the repository left the inventory", Instant.now());
      return;
    }
    String branch = bump.branch;
    String baseRef = baseRef(repository.get());

    // REFUSED HERE RATHER THAN OVER THERE. The step holds every one of these to the same rule, so
    // a payload that fails validation is a red run and a step log somebody has to read. Failing on
    // this side puts the reason on the bump row, written by the component that composed it.
    List<String> problems = BumpPayload.problems(bump.groupName, branch, baseRef, changes);
    if (!problems.isEmpty()) {
      store.bumpFinished(
          id, BumpStatus.FAILED, null, String.join("; ", problems), Instant.now());
      LOG.warnf("The bump %s was not sent: %s", id, problems);
      return;
    }

    // The head BEFORE the run, which is what an unmoved branch is compared against afterwards.
    recordBranchHead(repository.get(), bump.groupName, branch, null);

    CiClient.TriggerResult result =
        ci.trigger(id.toString(), bump.repository, bump.groupName, branch, baseRef, changes);
    switch (result.outcome()) {
      case ACCEPTED -> {
        store.bumpDispatched(id, result.eventId(), result.runIds());
        LOG.infof("qits-ci accepted the bump %s as run(s) %s", id, result.runIds());
      }
      case RETRY ->
          // Still REQUESTED, changes intact, same event id next time. The poller sends it again.
          store.bumpFinished(id, BumpStatus.REQUESTED, null, result.message(), Instant.now());
      case FAILED -> {
        store.bumpFinished(id, BumpStatus.FAILED, null, result.message(), Instant.now());
        LOG.warnf("The bump %s failed at the trigger: %s", id, result.message());
      }
    }
  }

  /** Follows one running bump to its end, or leaves it running. */
  public void poll(UUID id) {
    Optional<MtBump> found = store.bump(id);
    if (found.isEmpty()) {
      return;
    }
    MtBump bump = found.get();
    if (!BumpStatus.RUNNING.name().equals(bump.status)) {
      return;
    }
    List<String> runIds = runIds(bump);
    if (runIds.isEmpty()) {
      store.bumpFinished(
          id, BumpStatus.FAILED, null, "the bump has no ci run to follow", Instant.now());
      return;
    }

    boolean allPassed = true;
    String lastStatus = null;
    for (String runId : runIds) {
      CiClient.RunState state = ci.run(runId);
      if (state.status() == null) {
        // Unreadable is not terminal. The next poll asks again; a run that is really gone leaves a
        // bump RUNNING, which is an honest gap rather than a fabricated verdict.
        store.bumpRunStatus(id, null);
        LOG.debugf("The bump %s could not read run %s: %s", id, runId, state.error());
        return;
      }
      lastStatus = state.status();
      if (!state.terminal()) {
        store.bumpRunStatus(id, state.status());
        return;
      }
      allPassed = allPassed && state.passed();
    }
    finish(bump, allPassed, lastStatus);
  }

  /** Sends every REQUESTED bump again and follows every RUNNING one. */
  public void sweep() {
    for (MtBump bump : store.activeBumps()) {
      UUID id = bump.id;
      if (BumpStatus.REQUESTED.name().equals(bump.status)) {
        queue.submit("re-dispatch bump " + id, () -> dispatch(id));
      } else {
        queue.submit("poll bump " + id, () -> poll(id));
      }
    }
  }

  /**
   * The verdict, once every run is terminal.
   *
   * <p>The branch head is read again here, and the comparison against what was recorded before the
   * trigger is what separates the three endings.
   */
  private void finish(MtBump bump, boolean passed, String ciRunStatus) {
    Instant now = Instant.now();
    Optional<MtRepository> repository = store.repository(bump.repository);
    String branch = bump.branch;
    String before = store.branch(bump.repository, bump.groupName).map(row -> row.headSha).orElse(null);
    String after =
        repository
            .map(row -> branchHead(row, branch))
            .orElse(null);
    boolean moved = after != null && !after.equals(before);

    if (passed && moved) {
      store.recordBranch(bump.repository, bump.groupName, branch, BranchState.PUSHED, after, now);
      store.bumpFinished(
          bump.id,
          BumpStatus.SUCCEEDED,
          ciRunStatus,
          changes(bump).size() + " dependencies on " + branch,
          now);
      return;
    }
    if (passed) {
      // Green, and the branch is where it was. The step read the files and found the versions
      // already there — the pins moved between the scan and the run, or another bump got there
      // first.
      store.bumpFinished(
          bump.id,
          BumpStatus.NOTHING_TO_DO,
          ciRunStatus,
          "the run passed and " + branch + " did not move",
          now);
      return;
    }
    // Red. A branch that moved anyway is somebody's hand-written commit that the ff-only push
    // refused to overwrite — they own it now.
    BranchState state = moved ? BranchState.STALE : BranchState.FAILED;
    store.recordBranch(bump.repository, bump.groupName, branch, state, after, now);
    store.bumpFinished(
        bump.id,
        BumpStatus.FAILED,
        ciRunStatus,
        state == BranchState.STALE
            ? branch + " was rewritten by hand; nothing will be pushed onto it"
            : "the ci run ended " + ciRunStatus,
        now);
  }

  /** Reads the branch's head and writes it, so the next comparison has something to compare to. */
  private void recordBranchHead(MtRepository repository, String group, String branch, BranchState force) {
    String head = branchHead(repository, branch);
    BranchState state = force != null ? force : (head == null ? BranchState.NONE : BranchState.PUSHED);
    store.recordBranch(repository.name, group, branch, state, head, Instant.now());
  }

  /** The branch's head sha, or null when it does not exist or could not be read. */
  private String branchHead(MtRepository repository, String branch) {
    TreeLookup lookup = gitHost.head(repository.project, repository.name, branch);
    return lookup.found() ? lookup.headSha() : null;
  }

  /** The stored changes, back as the records they went out as. */
  public static List<Change> changes(MtBump bump) {
    List<Change> changes = new ArrayList<>();
    for (Map<String, Object> row : MaintenanceStore.readObjects(bump.changes)) {
      changes.add(
          new Change(
              string(row, "ecosystem"),
              string(row, "manifestPath"),
              string(row, "name"),
              string(row, "from"),
              string(row, "to"),
              string(row, "location")));
    }
    return changes;
  }

  private static String string(Map<String, Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : value.toString();
  }

  private static List<String> runIds(MtBump bump) {
    if (bump.ciRunId == null || bump.ciRunId.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(bump.ciRunId.split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .toList();
  }

  private static String baseRef(MtRepository repository) {
    return repository.mainBranch == null || repository.mainBranch.isBlank()
        ? "main"
        : repository.mainBranch;
  }

  /** The branch rows of one repository, for the API's group listing. */
  public List<MtBranch> branches(String repository) {
    return store.branches(repository);
  }
}
