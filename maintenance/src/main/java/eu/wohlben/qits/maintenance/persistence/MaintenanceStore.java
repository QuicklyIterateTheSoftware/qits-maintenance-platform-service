package eu.wohlben.qits.maintenance.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.db.DbRetry;
import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtArtifactComponent;
import eu.wohlben.qits.maintenance.entity.MtArtifactEdge;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.entity.MtScan;
import eu.wohlben.qits.maintenance.error.BumpAlreadyActiveException;
import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.manifest.GroupConfig;
import eu.wohlben.qits.maintenance.manifest.ParsedPin;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.model.BumpStatus;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.SbomStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.model.ScanStatus;
import eu.wohlben.qits.maintenance.sbom.ParsedSbom;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only writer of the seven tables, and the reader the API uses.
 *
 * <p><b>Every method activates a request context</b>, because the caller is usually the worker
 * thread and a Hibernate session is bound to that context. A route's call already has one and
 * activating a second is a no-op, so one annotation covers both callers.
 *
 * <p><b>Every write is a {@code DbRetry.inNewTx}.</b> {@code inNewTx} owns the transaction
 * boundary, which is the only way a retry can tell "the body threw, so it certainly never
 * committed" from "the transaction manager reported it" — Narayana spells a lost commit and a real
 * rollback with the same exception. Each write ends with a flush, which keeps a lost connection on
 * the body's side of that line.
 *
 * <p><b>One repository's inventory is replaced in ONE transaction.</b> Pins and groups are deleted
 * and rewritten together with the row that carries the sha they were read at; a scan that committed
 * the delete and failed the insert would leave a repository looking as though it pins nothing,
 * which is also what "nothing pending" looks like.
 */
@ApplicationScoped
public class MaintenanceStore implements PanacheRepositoryBase<MtRepository, String> {

  private static final ObjectMapper JSON = new ObjectMapper();

  // --- the inventory ------------------------------------------------------------------------

  /**
   * Replaces one repository's whole inventory: the row, its pins and its groups.
   *
   * @param kindOf what can be done with a pin — passed in rather than injected, because the rule is
   *     configuration and this class is storage
   */
  @ActivateRequestContext
  public void replaceInventory(
      String name,
      String project,
      String mainBranch,
      RepositoryStatus status,
      String headSha,
      String message,
      List<ParsedPin> pins,
      List<GroupConfig.Group> groups,
      GroupSource groupSource,
      java.util.function.Function<ParsedPin, PinKind> kindOf,
      Instant now) {
    DbRetry.runInNewTx(
        "replace the inventory of " + name,
        () -> {
          // EVERY FIELD BEFORE THE PERSIST. `MtPin.delete` below is a query, and Hibernate flushes
          // before one — so a row persisted with its not-null columns still unset fails the flush
          // rather than the insert, naming a column nobody was writing at the time.
          MtRepository row = findById(name);
          boolean fresh = row == null;
          if (fresh) {
            row = new MtRepository();
            row.name = name;
          }
          row.project = project;
          row.mainBranch = mainBranch;
          row.status = status.name();
          row.headSha = headSha;
          row.message = message;
          row.lastScanAt = now;
          if (fresh) {
            persist(row);
          }

          MtPin.delete("repository", name);
          for (ParsedPin pin : pins) {
            MtPin stored = new MtPin();
            stored.id = UUID.randomUUID();
            stored.repository = name;
            stored.manifestPath = pin.manifestPath();
            stored.ecosystem = pin.ecosystem().wireName();
            stored.name = pin.name();
            stored.version = pin.version();
            stored.range = pin.range();
            stored.kind = kindOf.apply(pin).name();
            stored.location = pin.location();
            stored.persist();
          }

          MtGroup.delete("repository", name);
          int ordinal = 0;
          for (GroupConfig.Group group : groups) {
            MtGroup stored = new MtGroup();
            stored.id = UUID.randomUUID();
            stored.repository = name;
            stored.name = group.name();
            stored.ordinal = ordinal++;
            stored.patterns = writeJson(group.patterns());
            // A KIND GROUP CLAIMS BY KIND AND CARRIES NO GLOBS; a configured one is the other way
            // round. The column is nullable because those are the two shapes, not three.
            stored.kind = group.kind() == null ? null : group.kind().name();
            stored.source = (groupSource == null ? GroupSource.DEFAULT : groupSource).name();
            stored.persist();
          }
          getEntityManager().flush();
        });
  }

  /**
   * Records an outcome that says nothing about the pins — an unreachable git host.
   *
   * <p><b>The pins are left standing.</b> A peer that could not be asked is not evidence that a
   * repository stopped pinning anything, and wiping the inventory on every hiccup would make the
   * UI's "pending" flicker to zero whenever the git host restarted.
   */
  @ActivateRequestContext
  public void markRepository(
      String name, String project, RepositoryStatus status, String message, Instant now) {
    DbRetry.runInNewTx(
        "mark " + name + " " + status,
        () -> {
          MtRepository row = findById(name);
          boolean fresh = row == null;
          if (fresh) {
            row = new MtRepository();
            row.name = name;
          }
          if (project != null) {
            row.project = project;
          }
          row.status = status.name();
          row.message = message;
          row.lastScanAt = now;
          if (fresh) {
            persist(row);
          }
          getEntityManager().flush();
        });
  }

  /** One dependency's latest, written whether the lookup succeeded or failed. */
  @ActivateRequestContext
  public void recordLatest(Ecosystem ecosystem, String name, LatestLookup lookup, Instant now) {
    DbRetry.runInNewTx(
        "record the latest of " + name,
        () -> {
          MtLatest row =
              MtLatest.find("ecosystem = ?1 and name = ?2", ecosystem.wireName(), name)
                  .firstResult();
          boolean fresh = row == null;
          if (fresh) {
            row = new MtLatest();
            row.id = UUID.randomUUID();
            row.ecosystem = ecosystem.wireName();
            row.name = name;
          }
          row.latest = lookup.latest();
          row.sourceUrl = lookup.sourceUrl();
          row.error = lookup.error();
          row.checkedAt = now;
          if (fresh) {
            row.persist();
          }
          getEntityManager().flush();
        });
  }

  /**
   * One dependency's latest, moved FORWARD ONLY — the bus's write, beside {@link #recordLatest}'s
   * polled one.
   *
   * <p><b>Why a second method rather than a flag on the first.</b> A poll ASKS a registry what the
   * newest version is and the answer replaces whatever was there, downgrades included: a package
   * that was unpublished really is behind now. An event ANNOUNCES one release, and an announcement
   * is only ever evidence that this version exists — never that a higher one does not. Letting the
   * bus write through {@code recordLatest} would let a catch-up frame from yesterday rewind a column
   * a scan filled this morning, and the whole inventory would show that dependency as up to date
   * until the next scan.
   *
   * <p>So the guard is {@link VersionOrder}'s, in that ecosystem's own order, and it is the same
   * comparison the pending rule makes. Three cases and only the first writes:
   *
   * <ul>
   *   <li>no row at all, or a row whose lookup FAILED ({@code latest} null) — the announcement is
   *       the first thing known about this dependency, so it is adopted;
   *   <li>a strictly newer version — the column moves and {@code error} is cleared;
   *   <li>anything else — an equal version (the ordinary redelivery), or an older one (a catch-up
   *       frame behind a scan) — and <b>nothing at all is written</b>, {@code checked_at} included.
   *       Stamping the timestamp would say a lookup happened, and none did.
   * </ul>
   *
   * <p>That makes it idempotent under redelivery by construction: the second offer of one release is
   * not newer than the first, so it is a read and a return.
   *
   * @param sourceUrl where the claim came from — the bus writes {@code event:<frame id>}, which is
   *     what tells a surprising row from a registry read
   * @return whether the column moved
   */
  @ActivateRequestContext
  public boolean recordLatestIfNewer(
      Ecosystem ecosystem, String name, String version, String sourceUrl, Instant now) {
    if (version == null || version.isBlank()) {
      return false;
    }
    return DbRetry.inNewTx(
        "record the announced latest of " + name,
        () -> {
          MtLatest row =
              MtLatest.find("ecosystem = ?1 and name = ?2", ecosystem.wireName(), name)
                  .firstResult();
          if (row != null
              && row.latest != null
              && !VersionOrder.newer(ecosystem, row.latest, version)) {
            return false;
          }
          boolean fresh = row == null;
          if (fresh) {
            row = new MtLatest();
            row.id = UUID.randomUUID();
            row.ecosystem = ecosystem.wireName();
            row.name = name;
          }
          row.latest = version;
          row.sourceUrl = sourceUrl;
          row.error = null;
          row.checkedAt = now;
          if (fresh) {
            row.persist();
          }
          getEntityManager().flush();
          return true;
        });
  }

  @ActivateRequestContext
  public List<MtRepository> repositories() {
    return listAll(Sort.by("name"));
  }

  @ActivateRequestContext
  public Optional<MtRepository> repository(String name) {
    return Optional.ofNullable(findById(name));
  }

  @ActivateRequestContext
  public List<MtPin> pins(String repository) {
    return MtPin.find("repository = ?1", Sort.by("manifestPath").and("name"), repository).list();
  }

  @ActivateRequestContext
  public List<MtPin> allPins() {
    return MtPin.findAll(Sort.by("repository").and("manifestPath").and("name")).list();
  }

  @ActivateRequestContext
  public List<MtGroup> groups(String repository) {
    return MtGroup.find("repository = ?1", Sort.by("ordinal"), repository).list();
  }

  @ActivateRequestContext
  public List<MtLatest> allLatest() {
    return MtLatest.listAll();
  }

  @ActivateRequestContext
  public Optional<MtLatest> latest(Ecosystem ecosystem, String name) {
    return Optional.ofNullable(
        MtLatest.find("ecosystem = ?1 and name = ?2", ecosystem.wireName(), name).firstResult());
  }

  // --- scans --------------------------------------------------------------------------------

  /** Opens a scan row, REQUESTED, before it is queued. */
  @ActivateRequestContext
  public UUID openScan(ScanScope scope, String repository, String trigger, Instant now) {
    return DbRetry.inNewTx(
        "open a " + scope + " scan",
        () -> {
          MtScan row = new MtScan();
          row.id = UUID.randomUUID();
          row.scope = scope.name();
          row.repository = repository;
          row.trigger = trigger;
          row.status = ScanStatus.REQUESTED.name();
          row.startedAt = now;
          row.persist();
          getEntityManager().flush();
          return row.id;
        });
  }

  /** Moves a scan along. A terminal status stamps {@code finished_at} and nothing else does. */
  @ActivateRequestContext
  public void scanStatus(UUID id, ScanStatus status, String message, Instant now) {
    DbRetry.runInNewTx(
        "set scan " + id + " " + status,
        () -> {
          MtScan row = MtScan.findById(id);
          if (row == null) {
            return;
          }
          row.status = status.name();
          if (message != null) {
            row.message = message;
          }
          if (status.terminal()) {
            row.finishedAt = now;
          }
          getEntityManager().flush();
        });
  }

  /**
   * Closes every scan a dead process left open, and answers how many there were.
   *
   * <p>A scan's work is entirely in this process — reads it made and rows it wrote — so a successor
   * cannot resume one and must not pretend it did. FAILED with a sentence is the honest record; the
   * next schedule scans again in minutes.
   */
  @ActivateRequestContext
  public long failInterruptedScans(String message, Instant now) {
    return DbRetry.inNewTx(
        "close the scans a restart interrupted",
        () -> {
          List<MtScan> open =
              MtScan.find(
                      "status in ?1",
                      List.of(ScanStatus.REQUESTED.name(), ScanStatus.RUNNING.name()))
                  .list();
          for (MtScan row : open) {
            row.status = ScanStatus.FAILED.name();
            row.message = message;
            row.finishedAt = now;
          }
          getEntityManager().flush();
          return (long) open.size();
        });
  }

  /**
   * Whether a scan of exactly this repository is already queued or running — the bus's debounce.
   *
   * <p>A push to a repository's main branch asks for that one repository to be re-read, and a burst
   * of pushes is the ordinary shape of a merge. Without this, five pushes in a minute are five scan
   * rows queued behind one worker thread, each re-reading a tree the one in front of it already
   * read.
   *
   * <p><b>It is repository-scoped exactly, and a whole-catalog scan is deliberately NOT counted.</b>
   * A full scan does cover this repository, so counting it would debounce correctly — but it runs
   * for minutes, and suppressing an event's rescan for the length of one would mean a push landing
   * during the nightly scan is read at whatever revision that scan happened to reach. One extra
   * git-host read behind a single-threaded queue is the cheaper mistake.
   */
  @ActivateRequestContext
  public boolean scanPending(String repository) {
    return MtScan.count(
            "repository = ?1 and status in ?2",
            repository,
            List.of(ScanStatus.REQUESTED.name(), ScanStatus.RUNNING.name()))
        > 0;
  }

  @ActivateRequestContext
  public Optional<MtScan> scan(UUID id) {
    return Optional.ofNullable(MtScan.findById(id));
  }

  /** The newest scans. */
  @ActivateRequestContext
  public List<MtScan> scans(int limit) {
    return MtScan.findAll(Sort.by("startedAt").descending()).page(0, limit).list();
  }

  // --- branches -----------------------------------------------------------------------------

  @ActivateRequestContext
  public List<MtBranch> branches(String repository) {
    return MtBranch.find("repository = ?1", Sort.by("groupName"), repository).list();
  }

  @ActivateRequestContext
  public Optional<MtBranch> branch(String repository, String group) {
    return Optional.ofNullable(
        MtBranch.find("repository = ?1 and groupName = ?2", repository, group).firstResult());
  }

  /** Writes what the git host says about one group's branch. */
  @ActivateRequestContext
  public void recordBranch(
      String repository,
      String group,
      String branchName,
      BranchState state,
      String headSha,
      Instant now) {
    DbRetry.runInNewTx(
        "record the branch of " + repository + "/" + group,
        () -> {
          MtBranch row =
              MtBranch.find("repository = ?1 and groupName = ?2", repository, group).firstResult();
          boolean fresh = row == null;
          if (fresh) {
            row = new MtBranch();
            row.id = UUID.randomUUID();
            row.repository = repository;
            row.groupName = group;
          }
          row.branch = branchName;
          row.state = state.name();
          row.headSha = headSha;
          row.updatedAt = now;
          if (fresh) {
            row.persist();
          }
          getEntityManager().flush();
        });
  }

  // --- bumps --------------------------------------------------------------------------------

  /**
   * Opens a bump.
   *
   * <p><b>The active-bump check is INSIDE the transaction</b> rather than a read before it. A
   * person pressing the button while a scheduled scan asks for the same group is the ordinary case,
   * and a check outside the write is a race whose prize is two runs pushing one branch.
   */
  @ActivateRequestContext
  public UUID openBump(
      String repository,
      String group,
      String branch,
      String environment,
      BumpTrigger trigger,
      List<?> changes,
      Instant now) {
    return DbRetry.inNewTx(
        "open a bump of " + repository + "/" + group,
        () -> {
          MtBump active = activeBumpRow(repository, group);
          if (active != null) {
            throw new BumpAlreadyActiveException(repository, group, active.id);
          }
          MtBump row = new MtBump();
          row.id = UUID.randomUUID();
          row.repository = repository;
          row.groupName = group;
          row.branch = branch;
          row.environment = environment;
          row.trigger = trigger.name();
          row.status = BumpStatus.REQUESTED.name();
          row.changes = writeJson(changes);
          row.startedAt = now;
          row.persist();
          getEntityManager().flush();
          return row.id;
        });
  }

  /** Records that qits-ci accepted the trigger and named its runs. */
  @ActivateRequestContext
  public void bumpDispatched(UUID id, String eventId, List<String> runIds) {
    DbRetry.runInNewTx(
        "dispatch bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null) {
            return;
          }
          row.ciEventId = eventId;
          row.ciRunId = runIds.isEmpty() ? null : String.join(",", runIds);
          row.status = BumpStatus.RUNNING.name();
          getEntityManager().flush();
        });
  }

  /** Records the CI run status a poll read, without ending the bump. */
  @ActivateRequestContext
  public void bumpRunStatus(UUID id, String ciRunStatus) {
    DbRetry.runInNewTx(
        "record the ci status of bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null) {
            return;
          }
          row.ciRunStatus = ciRunStatus;
          getEntityManager().flush();
        });
  }

  /** Closes a bump. */
  @ActivateRequestContext
  public void bumpFinished(
      UUID id, BumpStatus status, String ciRunStatus, String message, Instant now) {
    DbRetry.runInNewTx(
        "finish bump " + id,
        () -> {
          MtBump row = MtBump.findById(id);
          if (row == null) {
            return;
          }
          row.status = status.name();
          if (ciRunStatus != null) {
            row.ciRunStatus = ciRunStatus;
          }
          row.message = message;
          row.finishedAt = status.terminal() ? now : null;
          getEntityManager().flush();
        });
  }

  @ActivateRequestContext
  public Optional<MtBump> bump(UUID id) {
    return Optional.ofNullable(MtBump.findById(id));
  }

  /** The newest bumps, of one repository or of all of them. */
  @ActivateRequestContext
  public List<MtBump> bumps(String repository, int limit) {
    Sort newestFirst = Sort.by("startedAt").descending();
    if (repository == null || repository.isBlank()) {
      return MtBump.findAll(newestFirst).page(0, limit).list();
    }
    return MtBump.find("repository = ?1", newestFirst, repository).page(0, limit).list();
  }

  /** Every bump that has not ended — what the poller drives. */
  @ActivateRequestContext
  public List<MtBump> activeBumps() {
    return MtBump.find(
            "status in ?1",
            Sort.by("startedAt"),
            List.of(BumpStatus.REQUESTED.name(), BumpStatus.RUNNING.name()))
        .list();
  }

  /** The bump holding one branch's lock, if there is one. */
  @ActivateRequestContext
  public Optional<MtBump> activeBump(String repository, String group) {
    return Optional.ofNullable(activeBumpRow(repository, group));
  }

  private static MtBump activeBumpRow(String repository, String group) {
    return MtBump.find(
            "repository = ?1 and groupName = ?2 and status in ?3",
            repository,
            group,
            List.of(BumpStatus.REQUESTED.name(), BumpStatus.RUNNING.name()))
        .firstResult();
  }

  // --- the sbom graph -------------------------------------------------------------------------

  /**
   * The row an announced release leaves behind, PENDING, for the ingest to pick up.
   *
   * <p><b>The row IS the outbox and this method is deliberately not an upsert of everything.</b> A
   * coordinate already known is LEFT ALONE — its status, its error and its graph are the reading of
   * an immutable released version, and a redelivered frame is not new evidence about it. Without
   * that, every catch-up sweep would re-queue every release it re-offered and the ingest would ask
   * qits-artifacts the same question about the same bytes for ever.
   *
   * @return the row's id, whether it was created here or was already there
   */
  @ActivateRequestContext
  public UUID upsertArtifact(
      Ecosystem ecosystem,
      String name,
      String version,
      String repository,
      Instant occurredAt) {
    return DbRetry.inNewTx(
        "record the released artifact " + name + " " + version,
        () -> {
          MtArtifact row = artifactRow(ecosystem, name, version);
          if (row != null) {
            return row.id;
          }
          row = new MtArtifact();
          row.id = UUID.randomUUID();
          row.ecosystem = ecosystem.wireName();
          row.name = name;
          row.version = version;
          row.repository = repository;
          row.occurredAt = occurredAt;
          row.sbomStatus = SbomStatus.PENDING.name();
          row.persist();
          getEntityManager().flush();
          return row.id;
        });
  }

  /**
   * The manual backfill's write: create the row, or put an existing one back to PENDING.
   *
   * <p><b>This is the one thing that moves a MISSING or FAILED row.</b> Nothing retries either
   * automatically — a 404 is the ordinary permanent answer for a release published before the SBOM
   * route existed — so a person who knows a document has since been stored asks for it by hand, and
   * this is what that ask writes. The graph is left standing until the re-ingest replaces it: a row
   * with no components for a minute would read as an artifact that contains nothing.
   */
  @ActivateRequestContext
  public UUID requeueArtifact(
      Ecosystem ecosystem, String name, String version, String repository, Instant now) {
    return DbRetry.inNewTx(
        "re-queue the sbom of " + name + " " + version,
        () -> {
          MtArtifact row = artifactRow(ecosystem, name, version);
          if (row == null) {
            row = new MtArtifact();
            row.id = UUID.randomUUID();
            row.ecosystem = ecosystem.wireName();
            row.name = name;
            row.version = version;
            row.repository = repository;
            // NOW, because nobody announced this one: a manual backfill of a release from months
            // ago has no frame to take a moment from, and inventing one would put it in the middle
            // of the ordering the dependents view is built on.
            row.occurredAt = now;
            row.sbomStatus = SbomStatus.PENDING.name();
            row.persist();
          } else {
            row.sbomStatus = SbomStatus.PENDING.name();
            row.sbomError = null;
            if (repository != null && !repository.isBlank()) {
              row.repository = repository;
            }
          }
          getEntityManager().flush();
          return row.id;
        });
  }

  /**
   * The whole of one artifact's graph, replaced in ONE transaction, and the row marked INGESTED.
   *
   * <p><b>Wholesale, for {@code replaceInventory}'s reason.</b> A document is one reading of one
   * immutable release; merging a second reading into the first would leave components from a parse
   * this build has since corrected. And a transaction that committed the delete and failed the
   * insert would leave an INGESTED artifact containing nothing, which is indistinguishable from a
   * release with no dependencies.
   *
   * <p>The edges are given by INDEX into {@code components}, with {@code -1} for the root, because
   * the caller cannot know the ids until they are minted here.
   *
   * @param components what the document listed, in document order
   * @param edges who pulled in whom, by position in that list
   */
  @ActivateRequestContext
  public void replaceGraph(
      UUID artifactId,
      List<ParsedSbom.Component> components,
      List<ParsedSbom.Edge> edges,
      Instant now) {
    DbRetry.runInNewTx(
        "replace the sbom graph of " + artifactId,
        () -> {
          MtArtifact row = MtArtifact.findById(artifactId);
          if (row == null) {
            return;
          }
          // Edges first: they refer to the components, and the delete order is the insert order
          // reversed for the same reason a foreign key exists at all.
          MtArtifactEdge.delete("artifactId", artifactId);
          MtArtifactComponent.delete("artifactId", artifactId);

          List<UUID> ids = new java.util.ArrayList<>(components.size());
          for (ParsedSbom.Component component : components) {
            MtArtifactComponent stored = new MtArtifactComponent();
            // EVERY FIELD BEFORE THE PERSIST — the same rock replaceInventory names: a query below
            // makes Hibernate flush, and a row whose not-null columns are still unset fails the
            // flush rather than the insert.
            stored.id = UUID.randomUUID();
            stored.artifactId = artifactId;
            stored.bomRef = component.bomRef();
            stored.purl = component.purl();
            stored.ecosystem =
                component.ecosystem() == null ? null : component.ecosystem().wireName();
            stored.name = component.name();
            stored.version = component.version();
            stored.direct = component.direct();
            stored.persist();
            ids.add(stored.id);
          }
          for (ParsedSbom.Edge edge : edges) {
            if (edge.child() < 0 || edge.child() >= ids.size() || edge.parent() >= ids.size()) {
              continue;
            }
            MtArtifactEdge stored = new MtArtifactEdge();
            stored.id = UUID.randomUUID();
            stored.artifactId = artifactId;
            // -1 is the root, which is the artifact row itself and has no component id.
            stored.parentComponentId = edge.parent() < 0 ? null : ids.get(edge.parent());
            stored.childComponentId = ids.get(edge.child());
            stored.persist();
          }

          row.sbomStatus = SbomStatus.INGESTED.name();
          row.sbomError = null;
          row.ingestedAt = now;
          getEntityManager().flush();
        });
  }

  /** qits-artifacts holds no document for this coordinate. Terminal, and nothing retries it. */
  @ActivateRequestContext
  public void markArtifactMissing(UUID artifactId) {
    markArtifact(artifactId, SbomStatus.MISSING, null);
  }

  /** The document could not be read, and the sentence is what a person decides on. */
  @ActivateRequestContext
  public void markArtifactFailed(UUID artifactId, String error) {
    markArtifact(artifactId, SbomStatus.FAILED, error);
  }

  private void markArtifact(UUID artifactId, SbomStatus status, String error) {
    DbRetry.runInNewTx(
        "mark the sbom of " + artifactId + " " + status,
        () -> {
          MtArtifact row = MtArtifact.findById(artifactId);
          if (row == null) {
            return;
          }
          row.sbomStatus = status.name();
          row.sbomError = error;
          getEntityManager().flush();
        });
  }

  /** Every artifact still waiting for its document — what the sweep re-queues. */
  @ActivateRequestContext
  public List<MtArtifact> pendingArtifacts() {
    return MtArtifact.find("sbomStatus = ?1", Sort.by("occurredAt"), SbomStatus.PENDING.name())
        .list();
  }

  @ActivateRequestContext
  public Optional<MtArtifact> artifact(UUID id) {
    return Optional.ofNullable(MtArtifact.findById(id));
  }

  @ActivateRequestContext
  public Optional<MtArtifact> artifact(Ecosystem ecosystem, String name, String version) {
    return Optional.ofNullable(artifactRow(ecosystem, name, version));
  }

  private static MtArtifact artifactRow(Ecosystem ecosystem, String name, String version) {
    return MtArtifact.find(
            "ecosystem = ?1 and name = ?2 and version = ?3", ecosystem.wireName(), name, version)
        .firstResult();
  }

  /**
   * One embedding of one dependency: the artifact that ships it, and the component row that says
   * so.
   *
   * @param artifact the released artifact whose document listed it
   * @param component what the document said about it
   */
  public record Dependent(MtArtifact artifact, MtArtifactComponent component) {}

  /**
   * <b>WHO SHIPS A COPY OF THIS.</b> Every ingested artifact whose bill of materials names the
   * dependency, direct or transitive.
   *
   * <p><b>Read in two steps rather than as one join, deliberately.</b> {@code
   * mt_artifact_component} carries a plain uuid rather than a mapped association — the same flat
   * shape every other table here uses — so the join would have to be native SQL. Two indexed reads
   * (the {@code (ecosystem, name)} index, then the artifacts by id) answer the same question, and
   * the set is bounded by how many versions of how many libraries embed one dependency.
   *
   * @param newestPerArtifactOnly the DEFAULT view: one row per dependent artifact NAME, the newest
   *     released version of it. A library released fifty times would otherwise answer this question
   *     fifty times over and bury the one fact anybody wanted — which of our things still ship it.
   */
  @ActivateRequestContext
  public List<Dependent> dependents(
      Ecosystem ecosystem, String name, boolean newestPerArtifactOnly) {
    List<MtArtifactComponent> components =
        MtArtifactComponent.find("ecosystem = ?1 and name = ?2", ecosystem.wireName(), name).list();
    if (components.isEmpty()) {
      return List.of();
    }
    List<UUID> artifactIds =
        components.stream().map(component -> component.artifactId).distinct().toList();
    Map<UUID, MtArtifact> artifacts = new LinkedHashMap<>();
    for (MtArtifact row : MtArtifact.<MtArtifact>find("id in ?1", artifactIds).list()) {
      artifacts.put(row.id, row);
    }

    List<Dependent> found = new java.util.ArrayList<>();
    for (MtArtifactComponent component : components) {
      MtArtifact artifact = artifacts.get(component.artifactId);
      if (artifact != null) {
        found.add(new Dependent(artifact, component));
      }
    }
    // The dependent name, then newest first — the order the API serves and the order the
    // newest-per-name filter below reads.
    found.sort(dependentOrder());
    if (!newestPerArtifactOnly) {
      return List.copyOf(found);
    }
    List<Dependent> newest = new java.util.ArrayList<>();
    String previous = null;
    for (Dependent dependent : found) {
      String key = dependent.artifact().ecosystem + " " + dependent.artifact().name;
      if (!key.equals(previous)) {
        newest.add(dependent);
        previous = key;
      }
    }
    return List.copyOf(newest);
  }

  /** Dependent name ascending, then the newest release of it first. */
  private static java.util.Comparator<Dependent> dependentOrder() {
    return java.util.Comparator.comparing(
            (Dependent dependent) -> dependent.artifact().ecosystem + " " + dependent.artifact().name)
        .thenComparing(
            dependent -> dependent.artifact().occurredAt,
            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
        .thenComparing(dependent -> dependent.artifact().version);
  }

  /**
   * Every distinct {@code (ecosystem, name)} this platform has released, newest row first per
   * pair.
   *
   * <p>The listing the internal-libs page is built from. Distinct pairs of {@code mt_artifact}
   * rather than a union with the component table: a library nobody has released has nothing to say
   * about what it contains, and a component that is not itself an artifact of ours is somebody
   * else's package, which the dependency page already answers for.
   */
  @ActivateRequestContext
  public List<MtArtifact> newestArtifactPerName() {
    List<MtArtifact> all =
        MtArtifact.findAll(Sort.by("ecosystem").and("name").and("occurredAt", Sort.Direction.Descending))
            .list();
    List<MtArtifact> newest = new java.util.ArrayList<>();
    String previous = null;
    for (MtArtifact row : all) {
      String key = row.ecosystem + " " + row.name;
      if (!key.equals(previous)) {
        newest.add(row);
        previous = key;
      }
    }
    return List.copyOf(newest);
  }

  /** Every artifact one repository has released, newest first. */
  @ActivateRequestContext
  public List<MtArtifact> artifactsOfRepository(String repository) {
    return MtArtifact.find(
            "repository = ?1",
            Sort.by("ecosystem").and("name").and("occurredAt", Sort.Direction.Descending),
            repository)
        .list();
  }

  /** Everything one artifact's document listed. */
  @ActivateRequestContext
  public List<MtArtifactComponent> components(UUID artifactId) {
    return MtArtifactComponent.find("artifactId = ?1", Sort.by("name"), artifactId).list();
  }

  /** One artifact's adjacency, for walking "what pulled this in". */
  @ActivateRequestContext
  public List<MtArtifactEdge> edges(UUID artifactId) {
    return MtArtifactEdge.find("artifactId = ?1", artifactId).list();
  }

  // --- json ---------------------------------------------------------------------------------

  /** A list into the text column that holds it. Two columns are json and neither is queried by. */
  static String writeJson(Object value) {
    try {
      return JSON.writeValueAsString(value == null ? List.of() : value);
    } catch (Exception e) {
      throw new IllegalStateException("could not write a json column", e);
    }
  }

  /** A json array column back as a list of strings, empty when it does not read. */
  public static List<String> readStrings(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return List.of(JSON.readValue(json, String[].class));
    } catch (Exception e) {
      return List.of();
    }
  }

  /** A json array column back as a list of maps — the stored bump changes. */
  public static List<Map<String, Object>> readObjects(String json) {
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> read = JSON.readValue(json, List.class);
      return read == null ? List.of() : read;
    } catch (Exception e) {
      return List.of();
    }
  }
}
