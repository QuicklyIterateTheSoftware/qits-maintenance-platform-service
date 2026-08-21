package eu.wohlben.qits.maintenance.scan;

import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.catalog.CatalogReader;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.latest.LatestResolver;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.work.WorkQueue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * A scan: read the catalog, re-read every repository's manifests, refresh the latest versions the
 * scope covers, and — on a schedule, when configured — ask for the bumps that fall out.
 *
 * <p><b>The manifests are ALWAYS re-read, whatever the scope.</b> That is one git-host call per
 * repository plus a handful of blobs, and it is what keeps the inventory honest: a scope that
 * skipped it would report pending changes against pins somebody removed yesterday. The scope
 * governs the REGISTRY half — internal releases land many times a day and are one hop away, an
 * external index is a daily question.
 *
 * <p><b>A repository is scanned even when the last scan failed</b>, and an unreachable one keeps
 * the pins it had. A peer that could not be asked is not evidence that a repository stopped pinning
 * anything.
 *
 * <p><b>Auto-bump belongs to the SCHEDULE, not to the button.</b> A person pressing Scan is asking
 * what is out of date; a person who wants a branch presses Bump. The clock is the caller that has
 * standing instructions.
 */
@ApplicationScoped
public class ScanService {

  private static final Logger LOG = Logger.getLogger(ScanService.class);

  @Inject CatalogReader catalog;

  @Inject ManifestScanner manifests;

  @Inject LatestResolver resolver;

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject BumpService bumps;

  @Inject WorkQueue queue;

  /**
   * Queues a scan and answers at once.
   *
   * <p><b>The id is the QUEUED WORK's, and nothing stores it.</b> The model has no scan table — a
   * scan's outcome is the repository rows it wrote, which is what the UI reads — so the id is a
   * handle for the log line and the 202, not something to fetch later. It is in the answer because
   * a client that queued work is owed the name of what it queued.
   */
  public UUID request(ScanScope scope, String repository, ScanTrigger trigger) {
    UUID id = UUID.randomUUID();
    String what =
        "the " + trigger.name().toLowerCase(java.util.Locale.ROOT) + " " + scope + " scan " + id
            + (repository == null ? "" : " of " + repository);
    queue.submit(what, () -> run(scope, repository, trigger, what));
    return id;
  }

  /** One scan, start to finish, on the worker thread. */
  void run(ScanScope scope, String repository, ScanTrigger trigger, String what) {
    Instant now = Instant.now();
    CatalogReader.Result read = catalog.read();
    if (!read.ok()) {
      // NOTHING IS MARKED. A catalog this service could not read says nothing about any
      // repository, and writing UNREACHABLE across the whole inventory would turn one peer's
      // outage into seventy rows of noise.
      LOG.warnf("%s did nothing: %s", what, read.error());
      return;
    }
    List<CatalogEntry> entries =
        read.entries().stream()
            .filter(entry -> repository == null || entry.name().equals(repository))
            .toList();
    if (entries.isEmpty()) {
      LOG.warnf("%s matched no repository in the catalog", what);
      return;
    }

    for (CatalogEntry entry : entries) {
      scanOne(entry, now);
    }
    refreshLatest(scope, repository);

    if (trigger == ScanTrigger.SCHEDULED && config.bumpEnabled() && config.bumpAuto()) {
      autoBump(entries);
    }
    LOG.infof("%s finished over %d repositories", what, entries.size());
  }

  /** One repository's manifests, written into the inventory. */
  private void scanOne(CatalogEntry entry, Instant now) {
    ManifestScanner.Read read = manifests.read(entry);
    if (read.status() == RepositoryStatus.UNREACHABLE) {
      store.markRepository(entry.name(), entry.project(), read.status(), read.message(), now);
      return;
    }
    store.replaceInventory(
        entry.name(),
        entry.project(),
        entry.mainBranch(),
        read.status(),
        read.headSha(),
        read.message(),
        read.pins(),
        read.groups(),
        read.groupSource(),
        config::kindOf,
        now);
  }

  /**
   * The registry half.
   *
   * <p>One lookup per DEPENDENCY, not per pin: forty repositories pinning quarkus ask the registry
   * once. The set is read back out of the store rather than kept from the loop above, so a scan of
   * one repository still refreshes exactly that repository's dependencies.
   */
  private void refreshLatest(ScanScope scope, String repository) {
    Map<String, Lookup> wanted = new LinkedHashMap<>();
    List<MtPin> pins = repository == null ? store.allPins() : store.pins(repository);
    for (MtPin pin : pins) {
      Optional<Ecosystem> ecosystem = Ecosystem.of(pin.ecosystem);
      if (ecosystem.isEmpty()) {
        continue;
      }
      PinKind kind = PinKind.valueOf(pin.kind);
      if (!scope.covers(kind) || !resolver.resolvable(ecosystem.get(), kind)) {
        continue;
      }
      wanted.putIfAbsent(
          PendingChanges.key(pin.ecosystem, pin.name), new Lookup(ecosystem.get(), kind, pin.name));
    }
    for (Lookup lookup : wanted.values()) {
      LatestLookup latest = resolver.resolve(lookup.ecosystem(), lookup.kind(), lookup.name());
      store.recordLatest(lookup.ecosystem(), lookup.name(), latest, Instant.now());
    }
  }

  /**
   * One bump per group that has pending changes and no active bump.
   *
   * <p>A repository whose configuration did not parse is skipped outright — that is what
   * CONFIG_ERROR means. A group already being bumped is skipped by the store's own check, which is
   * where the refusal belongs; the exception is expected here and is not a fault.
   */
  private void autoBump(List<CatalogEntry> entries) {
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    for (CatalogEntry entry : entries) {
      Optional<MtRepository> row = store.repository(entry.name());
      if (row.isEmpty() || !RepositoryStatus.OK.name().equals(row.get().status)) {
        continue;
      }
      List<MtPin> pins = store.pins(entry.name());
      List<MtGroup> groups = store.groups(entry.name());
      Map<String, Integer> counts = PendingChanges.countByGroup(pins, latest, groups);
      for (Map.Entry<String, Integer> count : counts.entrySet()) {
        if (count.getValue() == 0) {
          continue;
        }
        if (store.activeBump(entry.name(), count.getKey()).isPresent()) {
          continue;
        }
        try {
          UUID id = bumps.request(entry.name(), count.getKey(), BumpTrigger.SCHEDULED);
          LOG.infof(
              "Requested the scheduled bump %s of %s/%s (%d changes)",
              id, entry.name(), count.getKey(), count.getValue());
        } catch (RuntimeException e) {
          // A refusal here is a branch somebody else is already writing, or a store that would not
          // answer. Either way the next scan asks again.
          LOG.warnf(
              "Could not request the scheduled bump of %s/%s: %s",
              entry.name(), count.getKey(), e.getMessage());
        }
      }
    }
  }

  /** One registry question: what is the newest version of this dependency. */
  private record Lookup(Ecosystem ecosystem, PinKind kind, String name) {}
}
