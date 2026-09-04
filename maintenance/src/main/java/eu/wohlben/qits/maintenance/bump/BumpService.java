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
 *
 * <p><b>SUCCEEDED asks for the release itself.</b> A branch nobody asks about is a branch that sits
 * there, so the ending that pushed one opens a release request in qits-projects — see {@link
 * ReleaseRequestClient} for what that ask is and why it is convergent. NOTHING_TO_DO and STALE do
 * not: there is nothing to release in the first case, and in the second somebody owns the branch by
 * hand and asking for their commits to be released is precisely the thing that must not happen.
 *
 * <p><b>The ask is where this service's part ends.</b> It opens a request; the quality gates settle
 * it, Auto Release tags it and the merge back to main follows the deployment, all in qits-projects.
 * Nothing here waits for a version, polls the request or records a release.
 */
@ApplicationScoped
public class BumpService {

  private static final Logger LOG = Logger.getLogger(BumpService.class);

  /**
   * Every maintenance branch is under this prefix. It is also what the release deletes: a request's
   * named sources are dropped when it lands, and the {@code SCMDeleteBranch} that follows is what
   * puts the branch row back to NONE.
   */
  public static final String BRANCH_PREFIX = "maintenance/";

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject CiClient ci;

  @Inject GitHostReader gitHost;

  @Inject ReleaseRequestClient releases;

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
    // Read for its refusal: a repository the inventory does not hold has no pins to bump and no
    // coordinate to name in a payload.
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
    recordBranchHead(repository.get(), bump.groupName, branch);

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

  /**
   * Three jobs: send every REQUESTED bump again, follow every RUNNING one, and ask qits-projects
   * again for every pushed branch whose release ask has not settled.
   */
  public void sweep() {
    for (MtBump bump : store.activeBumps()) {
      UUID id = bump.id;
      if (BumpStatus.REQUESTED.name().equals(bump.status)) {
        queue.submit("re-dispatch bump " + id, () -> dispatch(id));
      } else {
        queue.submit("poll bump " + id, () -> poll(id));
      }
    }
    for (MtBump bump : store.bumpsOwedARelease()) {
      UUID id = bump.id;
      queue.submit("ask for the release of bump " + id, () -> retryRelease(id));
    }
  }

  /**
   * One more attempt at the release ask, for a bump that pushed a branch and got no answer worth
   * keeping.
   *
   * <p><b>It is bounded by the BRANCH, not by a counter.</b> There is no attempt limit and no backoff
   * schedule, because the thing that ends the retrying is the thing the retrying is for: the branch
   * either gets a release request (the column fills) or vanishes (NONE, from {@code
   * SCMDeleteBranch} — which is also what a landed release leaves behind, since a request's named
   * sources are deleted when it lands). A counter would additionally have to be right about how long
   * qits-projects may be down for, which is not a question this service can answer.
   *
   * <p>So each ending writes the column and the row stops being read, and until one of them happens
   * qits-projects is asked once per poll tick — which is the same tick that follows a running CI run
   * and is a no-op whenever nothing is owed.
   */
  public void retryRelease(UUID id) {
    Optional<MtBump> found = store.bump(id);
    if (found.isEmpty()) {
      return;
    }
    MtBump bump = found.get();
    if (!BumpStatus.SUCCEEDED.name().equals(bump.status) || bump.releaseRequestId != null) {
      // Settled between the sweep's read and this task. Idempotent by design: the sweep queues onto
      // one worker thread and a second tick can land behind the first.
      return;
    }
    Optional<MtBranch> branch = store.branch(bump.repository, bump.groupName);
    String state = branch.map(row -> row.state).orElse(null);
    if (!BranchState.PUSHED.name().equals(state)) {
      // NONE, STALE, or no row at all. In every one of them the branch this bump pushed is no
      // longer this bump's to ask about — it is gone, or somebody owns it by hand.
      store.bumpReleaseAsked(
          id,
          ReleaseRequestClient.CONVERGED,
          note(
              bump,
              bump.branch + " is " + (state == null ? "no longer tracked" : state)
                  + "; no release was asked for again"));
      return;
    }
    askForRelease(bump);
  }

  /**
   * Opens a release request in qits-projects for the branch this bump pushed, and records what came
   * back.
   *
   * <p>Everything about WHY the ask looks like this is in {@link ReleaseRequestClient}; what belongs
   * here is the one refusal that is this service's own: a repository row with no catalog id. The ask
   * is addressed by qits-projects' OWN row id, which {@code CatalogReader} copies onto {@code
   * mt_repository.catalog_id} — a row the catalog listed without one, or one no scan has re-read
   * since that column existed, cannot address the route at all. That is a refusal rather than a
   * retry, because no number of attempts adds an id to it; the next scan does, and the next bump
   * then asks with it.
   */
  private void askForRelease(MtBump bump) {
    Optional<MtRepository> repository = store.repository(bump.repository);
    String repoId = repository.map(row -> row.catalogId).orElse(null);
    if (repoId == null || repoId.isBlank()) {
      store.bumpReleaseAsked(
          bump.id,
          ReleaseRequestClient.REFUSED,
          note(
              bump,
              "the release request cannot be addressed: "
                  + bump.repository
                  + " has no catalog id on its inventory row"));
      LOG.warnf(
          "The bump %s pushed %s but has no catalog id to address qits-projects with",
          bump.id, bump.branch);
      return;
    }
    ReleaseRequestClient.RequestResult result =
        releases.requestRelease(
            repoId,
            bump.branch,
            ReleaseRequestClient.summary(bump.groupName, changes(bump).size()));
    store.bumpReleaseAsked(bump.id, result.requestId(), note(bump, result.message()));
    switch (result.outcome()) {
      case REQUESTED ->
          LOG.infof(
              "The bump %s asked for %s to be released: request %s",
              bump.id, bump.branch, result.requestId());
      case CONVERGED ->
          LOG.infof("The bump %s has nothing left to ask about %s", bump.id, bump.branch);
      case REFUSED ->
          LOG.warnf("The release request for %s was refused: %s", bump.branch, result.message());
      case RETRY ->
          LOG.warnf(
              "qits-projects did not answer the release request for %s: %s; the next sweep asks"
                  + " again",
              bump.branch, result.message());
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
      store.bumpFinished(bump.id, BumpStatus.SUCCEEDED, ciRunStatus, pushedMessage(bump), now);
      // THE ROW IS CLOSED BEFORE THE RELEASE IS ASKED FOR, and the order is the failure policy. The
      // bump succeeded on the strength of the run and the head; a qits-projects that will not answer
      // must not be able to change that verdict, and a process that died between the two lines
      // leaves a SUCCEEDED bump the sweep picks up rather than a bump with no ending at all.
      askForRelease(bump);
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

  /**
   * What a SUCCEEDED bump's message says on its own — the sentence the ending writes, before the
   * release ask has anything to add.
   */
  private static String pushedMessage(MtBump bump) {
    return changes(bump).size() + " dependencies on " + bump.branch;
  }

  /**
   * The bump's own sentence with the release ask's beside it.
   *
   * <p><b>Recomposed from the ending rather than appended to whatever is there.</b> The ask is
   * retried once per poll tick until it settles, and an append would grow the column by a line every
   * fifteen seconds for the length of an outage. This is idempotent: the base is derived from the
   * frozen change list and the branch, so N attempts leave one base and the newest note.
   */
  private static String note(MtBump bump, String note) {
    return note == null || note.isBlank() ? pushedMessage(bump) : pushedMessage(bump) + " — " + note;
  }

  /** Reads the branch's head and writes it, so the next comparison has something to compare to. */
  private void recordBranchHead(MtRepository repository, String group, String branch) {
    String head = branchHead(repository, branch);
    BranchState state = head == null ? BranchState.NONE : BranchState.PUSHED;
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
