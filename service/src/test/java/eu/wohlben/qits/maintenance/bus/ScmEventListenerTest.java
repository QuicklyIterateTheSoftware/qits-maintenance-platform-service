package eu.wohlben.qits.maintenance.bus;

import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.frame;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.scmDeleteBranchPayload;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.scmPublishCommitPayload;
import static eu.wohlben.qits.maintenance.bus.ForeignEventContractTest.scmReleasePayload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The SCM listener's three decisions, in isolation from the bus, the database and the work queue.
 *
 * <p>The stand-ins are a {@link MaintenanceStore} holding rows in maps and a {@link ScanService} that
 * records what it was asked for instead of queueing it. That is the whole seam: what a scan then
 * does is {@code ScanCycleIT}'s and {@code MaintenanceApiTest}'s, and what a branch row means to a
 * bump is {@code BumpIT}'s.
 *
 * <p>Every payload is produced by {@link ForeignEventContractTest}'s transcription through the real
 * canonical serializer, so the bytes are the bytes qits-workspaces and qits-githost publish.
 */
class ScmEventListenerTest {

  private static final String REPOSITORY = "qits-ci-service";
  private static final String MAIN = "main";
  private static final String GROUP = "dependencies";
  private static final String BRANCH = "maintenance/" + GROUP;

  /** A store whose seven tables are three maps. */
  private static final class RecordingStore extends MaintenanceStore {

    final Map<String, MtRepository> repositories = new LinkedHashMap<>();
    final Map<String, List<MtGroup>> groups = new LinkedHashMap<>();
    final Map<String, MtBranch> branches = new LinkedHashMap<>();
    final Set<String> pendingScans = new java.util.LinkedHashSet<>();
    RuntimeException failWith;

    void repository(String name, String mainBranch, String... groupNames) {
      MtRepository row = new MtRepository();
      row.name = name;
      row.project = "qits";
      row.mainBranch = mainBranch;
      repositories.put(name, row);
      List<MtGroup> declared = new ArrayList<>();
      for (String groupName : groupNames) {
        MtGroup group = new MtGroup();
        group.id = UUID.randomUUID();
        group.repository = name;
        group.name = groupName;
        declared.add(group);
      }
      groups.put(name, declared);
    }

    void branch(String repository, String group, BranchState state, String headSha) {
      MtBranch row = new MtBranch();
      row.id = UUID.randomUUID();
      row.repository = repository;
      row.groupName = group;
      row.branch = "maintenance/" + group;
      row.state = state.name();
      row.headSha = headSha;
      branches.put(repository + "/" + group, row);
    }

    @Override
    public Optional<MtRepository> repository(String name) {
      if (failWith != null) {
        throw failWith;
      }
      return Optional.ofNullable(repositories.get(name));
    }

    @Override
    public List<MtGroup> groups(String repository) {
      return groups.getOrDefault(repository, List.of());
    }

    @Override
    public Optional<MtBranch> branch(String repository, String group) {
      return Optional.ofNullable(branches.get(repository + "/" + group));
    }

    @Override
    public void recordBranch(
        String repository,
        String group,
        String branchName,
        BranchState state,
        String headSha,
        Instant now) {
      MtBranch row = new MtBranch();
      row.id = UUID.randomUUID();
      row.repository = repository;
      row.groupName = group;
      row.branch = branchName;
      row.state = state.name();
      row.headSha = headSha;
      row.updatedAt = now;
      branches.put(repository + "/" + group, row);
    }

    @Override
    public boolean scanPending(String repository) {
      return pendingScans.contains(repository);
    }

    MtBranch branchRow(String repository, String group) {
      return branches.get(repository + "/" + group);
    }
  }

  /** A scan service that records the request instead of opening a row and queueing work. */
  private static final class RecordingScans extends ScanService {

    record Requested(ScanScope scope, String repository, ScanTrigger trigger) {}

    final List<Requested> requested = new ArrayList<>();

    @Override
    public UUID request(ScanScope scope, String repository, ScanTrigger trigger) {
      requested.add(new Requested(scope, repository, trigger));
      return UUID.randomUUID();
    }
  }

  private ScmEventListener listener;
  private RecordingStore store;
  private RecordingScans scans;

  @BeforeEach
  void setUp() {
    store = new RecordingStore();
    scans = new RecordingScans();
    listener = new ScmEventListener();
    listener.store = store;
    listener.scans = scans;
    store.repository(REPOSITORY, MAIN, GROUP, "external");
  }

  private void released(String repository, String branch) {
    listener.onFrame(frame("SCMRelease", scmReleasePayload(repository, branch, "2026.901.1")));
  }

  private void deleted(String repository, String branch) {
    listener.onFrame(frame("SCMDeleteBranch", scmDeleteBranchPayload(repository, branch)));
  }

  private void pushed(String repository, String branch, String sha) {
    listener.onFrame(frame("SCMPublishCommit", scmPublishCommitPayload(repository, branch, sha)));
  }

  @Test
  void itSubscribesToTheThreeSignaturesUnderItsOwnStorageKey() {
    assertEquals(
        Set.of("SCMRelease", "SCMDeleteBranch", "SCMPublishCommit"), listener.signatures());
    assertEquals("maintenance-branch-tracking", listener.consumerId());
  }

  // --- the branch's life ------------------------------------------------------------------------

  /**
   * The whole point of consuming SCMRelease: RELEASED is a state only the release door can report,
   * and it is what tells a released branch from one a person deleted.
   */
  @Test
  void aReleasedMaintenanceBranchBecomesRELEASEDAndThenTheDeleteMakesItNONE() {
    store.branch(REPOSITORY, GROUP, BranchState.PUSHED, "abc1234");

    released(REPOSITORY, BRANCH);
    MtBranch afterRelease = store.branchRow(REPOSITORY, GROUP);
    assertEquals(BranchState.RELEASED.name(), afterRelease.state);
    assertEquals(
        "abc1234",
        afterRelease.headSha,
        "at this instant the branch is released and not yet deleted, so its head is still known");

    deleted(REPOSITORY, BRANCH);
    MtBranch afterDelete = store.branchRow(REPOSITORY, GROUP);
    assertEquals(BranchState.NONE.name(), afterDelete.state);
    assertNull(afterDelete.headSha, "a branch that is gone has no head, and the next bump starts fresh");
  }

  /** The same delete is the only thing that ever clears a branch somebody rewrote by hand. */
  @Test
  void aHandDeletedStaleBranchIsClearedByTheDeleteEvent() {
    store.branch(REPOSITORY, GROUP, BranchState.STALE, "somebody-elses-sha");

    deleted(REPOSITORY, BRANCH);

    assertEquals(BranchState.NONE.name(), store.branchRow(REPOSITORY, GROUP).state);
  }

  /** Every release on the platform rides this signature and almost none of them is one of ours. */
  @Test
  void aReleaseOfAnOrdinaryBranchTouchesNothing() {
    released(REPOSITORY, MAIN);
    deleted(REPOSITORY, "feature/something");

    assertTrue(store.branches.isEmpty());
  }

  @Test
  void aReleaseOfAnUnknownRepositoryOrGroupIsSettled() {
    released("some-repository-nobody-scanned", BRANCH);
    released(REPOSITORY, "maintenance/a-group-this-repository-does-not-have");

    assertTrue(store.branches.isEmpty());
  }

  // --- the push ---------------------------------------------------------------------------------

  @Test
  void aPushToTheRepositorysMainBranchQueuesAScanOfThatOneRepository() {
    pushed(REPOSITORY, MAIN, "abc1234");

    assertEquals(1, scans.requested.size());
    RecordingScans.Requested queued = scans.requested.get(0);
    assertEquals(REPOSITORY, queued.repository());
    assertEquals(ScanTrigger.EVENT, queued.trigger());
    assertEquals(
        ScanScope.INTERNAL,
        queued.scope(),
        "every scan re-reads every manifest; the scope governs only which registries answer");
  }

  /** A merge is a burst of pushes, and a scan already queued reads the head at the moment it runs. */
  @Test
  void aSecondPushIsDebouncedAgainstTheScanAlreadyQueued() {
    pushed(REPOSITORY, MAIN, "abc1234");
    store.pendingScans.add(REPOSITORY);
    pushed(REPOSITORY, MAIN, "def5678");

    assertEquals(1, scans.requested.size(), "one queued scan covers both pushes");
  }

  @Test
  void aPushToAnyOtherBranchQueuesNothing() {
    pushed(REPOSITORY, BRANCH, "abc1234");
    pushed(REPOSITORY, "feature/whatever", "abc1234");

    assertTrue(
        scans.requested.isEmpty(),
        "only the branch a scan reads manifests at can invalidate the inventory");
  }

  /** A repository no scan has read yet has no row and no main branch to compare against. */
  @Test
  void aPushToARepositoryThisInventoryDoesNotHoldIsSettled() {
    pushed("some-repository-nobody-scanned", MAIN, "abc1234");

    assertTrue(scans.requested.isEmpty());
  }

  /** An id-addressed push — a mirror sync — announces neither a project nor a name. */
  @Test
  void aPushThatNamesNoRepositoryIsSettled() {
    listener.onFrame(frame("SCMPublishCommit", scmPublishCommitPayload(null, MAIN, "abc1234")));

    assertTrue(scans.requested.isEmpty());
  }

  // --- failure ----------------------------------------------------------------------------------

  @Test
  void anUnreadablePayloadIsPoisonAndIsSettledOnEverySignature() {
    listener.onFrame(frame("SCMRelease", "not json"));
    listener.onFrame(frame("SCMDeleteBranch", "not json"));
    listener.onFrame(frame("SCMPublishCommit", "not json"));

    assertTrue(store.branches.isEmpty());
    assertTrue(scans.requested.isEmpty());
  }

  @Test
  void aStoreThatWillNotAnswerIsLeftToThrowSoTheEventStaysOwed() {
    store.failWith = new IllegalStateException("the database is not there");

    assertThrows(IllegalStateException.class, () -> pushed(REPOSITORY, MAIN, "abc1234"));
  }
}
