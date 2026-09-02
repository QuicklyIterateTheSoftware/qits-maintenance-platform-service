package eu.wohlben.qits.maintenance.scan;

import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.catalog.CatalogReader;
import eu.wohlben.qits.maintenance.config.MaintenanceConfig;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.latest.LatestLookup;
import eu.wohlben.qits.maintenance.latest.LatestResolver;
import eu.wohlben.qits.maintenance.manifest.ManifestScanner;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.model.ScanStatus;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
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
 * A scan: read the catalog, re-read every repository's manifests, and refresh the latest versions
 * the scope covers. That is the whole of it — it writes an inventory and asks for nothing.
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
 * <p><b>A SCAN NEVER BUMPS ANYTHING, whoever asked for it.</b> It used to: a SCHEDULED scan whose
 * {@code bump.auto} was on asked for one bump per group it found pending, at the end of its own run.
 * That is gone, and the reason is that it welded two decisions together. A scan is a READ — of the
 * catalog, of every manifest, of half the registries — and its schedule is set by how fast those
 * facts go stale; a bump is a WRITE into somebody else's repository, and its schedule is set by when
 * a branch is welcome. Coupling them meant the external scan's 01:00 read decided when the internal
 * half got a branch, and moving either cron moved the other's meaning. The clock still has standing
 * instructions — they live in {@code schedule/BumpSchedule}, which reads the inventory this service
 * wrote and asks for the bumps on its own cron.
 */
@ApplicationScoped
public class ScanService {

  private static final Logger LOG = Logger.getLogger(ScanService.class);

  @Inject CatalogReader catalog;

  @Inject ManifestScanner manifests;

  @Inject LatestResolver resolver;

  @Inject MaintenanceStore store;

  @Inject MaintenanceConfig config;

  @Inject WorkQueue queue;

  /**
   * Opens a scan row, queues the work and answers at once.
   *
   * <p><b>The row exists before the work does.</b> {@code POST /scans} answers 202 with this id and
   * the client polls {@code GET /scans/{id}} — a scan of the whole catalog is one git-host read per
   * repository and a registry lookup per dependency, so a person who pressed the button has to be
   * able to see that something is happening rather than guess from a listing that has not changed
   * yet.
   */
  public UUID request(ScanScope scope, String repository, ScanTrigger trigger) {
    UUID id = store.openScan(scope, repository, trigger.name(), Instant.now());
    String what =
        "the " + trigger.name().toLowerCase(java.util.Locale.ROOT) + " " + scope + " scan " + id
            + (repository == null ? "" : " of " + repository);
    queue.submit(what, () -> run(id, scope, repository, what));
    return id;
  }

  /**
   * One scan, start to finish, on the worker thread.
   *
   * <p><b>It never lets an exception escape without closing the row.</b> The first live scan over
   * 49 repositories died inside a latest lookup, and because nothing caught it the row stayed
   * RUNNING for ever — a scan that is not running and does not say so is worse than a failed one,
   * because nothing and nobody can tell the difference from a slow one.
   */
  void run(UUID id, ScanScope scope, String repository, String what) {
    try {
      scan(id, scope, repository, what);
    } catch (RuntimeException e) {
      LOG.errorf(e, "%s could not be completed", what);
      store.scanStatus(id, ScanStatus.FAILED, message(e), Instant.now());
    }
  }

  /** What a scan does, with the row's ending left to {@link #run}. */
  private void scan(UUID id, ScanScope scope, String repository, String what) {
    Instant now = Instant.now();
    store.scanStatus(id, ScanStatus.RUNNING, null, now);
    CatalogReader.Result read = catalog.read();
    if (!read.ok()) {
      // NOTHING IS MARKED. A catalog this service could not read says nothing about any
      // repository, and writing UNREACHABLE across the whole inventory would turn one peer's
      // outage into seventy rows of noise.
      LOG.warnf("%s did nothing: %s", what, read.error());
      store.scanStatus(id, ScanStatus.FAILED, read.error(), Instant.now());
      return;
    }
    List<CatalogEntry> entries =
        read.entries().stream()
            .filter(entry -> repository == null || entry.name().equals(repository))
            .toList();
    if (entries.isEmpty()) {
      LOG.warnf("%s matched no repository in the catalog", what);
      store.scanStatus(
          id, ScanStatus.FAILED, "no repository in the catalog matched", Instant.now());
      return;
    }

    for (CatalogEntry entry : entries) {
      scanOne(entry, now);
    }
    refreshLatest(scope, repository);

    LOG.infof("%s finished over %d repositories", what, entries.size());
    store.scanStatus(
        id,
        ScanStatus.SUCCEEDED,
        entries.size() + " repositories read at " + scope + " scope",
        Instant.now());
  }

  /**
   * One repository's manifests, written into the inventory.
   *
   * <p>A repository that blows up is that repository's row, not the scan's: forty-eight others
   * still have manifests worth reading.
   */
  private void scanOne(CatalogEntry entry, Instant now) {
    try {
      readInto(entry, now);
    } catch (RuntimeException e) {
      LOG.warnf(e, "%s could not be scanned", entry.name());
      store.markRepository(
          entry.name(),
          entry.project(),
          entry.catalogId(),
          RepositoryStatus.UNREACHABLE,
          message(e),
          now);
    }
  }

  private void readInto(CatalogEntry entry, Instant now) {
    ManifestScanner.Read read = manifests.read(entry);
    if (read.status() == RepositoryStatus.UNREACHABLE) {
      store.markRepository(
          entry.name(), entry.project(), entry.catalogId(), read.status(), read.message(), now);
      return;
    }
    store.replaceInventory(
        entry.name(),
        entry.project(),
        entry.catalogId(),
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
      // The resolver already turns everything into an answer; this is the belt to its braces, so
      // that even a bug in the resolver itself costs one dependency rather than the scan.
      LatestLookup latest;
      try {
        latest = resolver.resolve(lookup.ecosystem(), lookup.kind(), lookup.name());
      } catch (RuntimeException e) {
        LOG.warnf("The latest of %s could not be looked up: %s", lookup.name(), e.toString());
        latest = LatestLookup.failed(null, "the lookup failed: " + e);
      }
      try {
        store.recordLatest(lookup.ecosystem(), lookup.name(), latest, Instant.now());
      } catch (RuntimeException e) {
        LOG.warnf("The latest of %s could not be written: %s", lookup.name(), e.toString());
      }
    }
  }

  /** An exception as a row's message: the sentence, never a stack trace, never null. */
  private static String message(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.toString() : e.getClass().getSimpleName() + ": " + message;
  }

  /** One registry question: what is the newest version of this dependency. */
  private record Lookup(Ecosystem ecosystem, PinKind kind, String name) {}
}
