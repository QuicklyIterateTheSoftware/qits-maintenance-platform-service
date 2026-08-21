package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.dto.BumpDto;
import eu.wohlben.qits.maintenance.dto.DependencyDto;
import eu.wohlben.qits.maintenance.dto.GroupDto;
import eu.wohlben.qits.maintenance.dto.PinDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDetailDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDto;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.error.NoSuchBumpException;
import eu.wohlben.qits.maintenance.error.NoSuchRepositoryException;
import eu.wohlben.qits.maintenance.manifest.Globs;
import eu.wohlben.qits.maintenance.model.BranchState;
import eu.wohlben.qits.maintenance.pending.PendingChanges;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The read side: the inventory and the bump log, in the shapes the API serves.
 *
 * <p>It sits in the domain jar because the shapes are the context's, not the web layer's —
 * {@code service}'s controllers do routing, roles and status codes and nothing else.
 *
 * <p><b>Pending is computed here on every read</b>, from the pins, the latest rows and the groups.
 * Three tables and no cache: a stored count would go stale the moment either half moved, and the
 * two halves move on different schedules by design.
 */
@ApplicationScoped
public class Inventory {

  @Inject MaintenanceStore store;

  @Inject BumpService bumps;

  /** Every repository, with its groups and what each has pending. */
  public List<RepositoryDto> repositories() {
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    List<RepositoryDto> listing = new ArrayList<>();
    for (MtRepository row : store.repositories()) {
      List<MtPin> pins = store.pins(row.name);
      List<MtGroup> groups = store.groups(row.name);
      List<GroupDto> groupDtos = groups(row.name, pins, latest, groups);
      listing.add(
          new RepositoryDto(
              row.name,
              row.project,
              row.lastScanAt,
              row.headSha,
              row.status,
              row.message,
              groupDtos.stream().mapToInt(GroupDto::pending).sum(),
              groupDtos));
    }
    return List.copyOf(listing);
  }

  /** One repository with every pin it holds. */
  public RepositoryDetailDto repository(String name) {
    MtRepository row = store.repository(name).orElseThrow(() -> new NoSuchRepositoryException(name));
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    List<MtPin> pins = store.pins(name);
    List<MtGroup> groups = store.groups(name);
    List<GroupDto> groupDtos = groups(name, pins, latest, groups);
    List<PinDto> pinDtos = new ArrayList<>();
    for (MtPin pin : pins) {
      pinDtos.add(pin(pin, latest, groups));
    }
    return new RepositoryDetailDto(
        row.name,
        row.project,
        row.lastScanAt,
        row.headSha,
        row.status,
        row.message,
        groupDtos.stream().mapToInt(GroupDto::pending).sum(),
        groupDtos,
        List.copyOf(pinDtos));
  }

  /**
   * Every dependency whose name matches the glob, with everyone who pins it.
   *
   * <p>A blank glob is every dependency: the page it serves is a searchable list, and an empty box
   * should show the list rather than nothing.
   */
  public List<DependencyDto> dependencies(String glob) {
    String pattern = glob == null || glob.isBlank() ? "*" : glob.trim();
    Map<String, MtLatest> latest = PendingChanges.index(store.allLatest());
    Map<String, List<MtPin>> byDependency = new LinkedHashMap<>();
    for (MtPin pin : store.allPins()) {
      if (!Globs.matches(pattern, pin.name)) {
        continue;
      }
      byDependency
          .computeIfAbsent(PendingChanges.key(pin.ecosystem, pin.name), key -> new ArrayList<>())
          .add(pin);
    }
    List<DependencyDto> listing = new ArrayList<>();
    for (Map.Entry<String, List<MtPin>> entry : byDependency.entrySet()) {
      MtPin first = entry.getValue().get(0);
      MtLatest row = latest.get(entry.getKey());
      List<DependencyDto.DependencyPinDto> pins = new ArrayList<>();
      for (MtPin pin : entry.getValue()) {
        pins.add(
            new DependencyDto.DependencyPinDto(
                pin.repository,
                pin.version,
                pin.manifestPath,
                PendingChanges.newerVersion(pin, latest).isPresent()));
      }
      listing.add(
          new DependencyDto(
              first.ecosystem,
              first.name,
              row == null ? null : row.latest,
              row == null ? null : row.checkedAt,
              row == null ? null : row.error,
              List.copyOf(pins)));
    }
    return List.copyOf(listing);
  }

  /** The newest bumps, of one repository or of all of them. */
  public List<BumpDto> bumps(String repository, int limit) {
    return store.bumps(repository, limit).stream().map(Inventory::bump).toList();
  }

  /** One bump. */
  public BumpDto bump(UUID id) {
    return bump(store.bump(id).orElseThrow(() -> new NoSuchBumpException(id)));
  }

  private List<GroupDto> groups(
      String repository, List<MtPin> pins, Map<String, MtLatest> latest, List<MtGroup> groups) {
    Map<String, Integer> counts = PendingChanges.countByGroup(pins, latest, groups);
    Map<String, MtBranch> branches = new LinkedHashMap<>();
    for (MtBranch branch : bumps.branches(repository)) {
      branches.put(branch.groupName, branch);
    }
    List<GroupDto> listing = new ArrayList<>();
    for (MtGroup group : groups) {
      MtBranch branch = branches.get(group.name);
      listing.add(
          new GroupDto(
              group.name,
              group.source,
              branch == null ? BumpService.BRANCH_PREFIX + group.name : branch.branch,
              branch == null ? BranchState.NONE.name() : branch.state,
              branch == null ? null : branch.headSha,
              counts.getOrDefault(group.name, 0)));
    }
    return List.copyOf(listing);
  }

  private static PinDto pin(MtPin pin, Map<String, MtLatest> latest, List<MtGroup> groups) {
    MtLatest row = latest.get(PendingChanges.key(pin.ecosystem, pin.name));
    Optional<String> newer = PendingChanges.newerVersion(pin, latest);
    return new PinDto(
        pin.manifestPath,
        pin.ecosystem,
        pin.name,
        pin.version,
        pin.range,
        pin.kind,
        row == null ? null : row.latest,
        row == null ? null : row.error,
        newer.isPresent(),
        PendingChanges.groupOf(pin.name, groups).orElse(null),
        pin.location);
  }

  private static BumpDto bump(MtBump row) {
    return new BumpDto(
        row.id,
        row.repository,
        row.groupName,
        BumpService.BRANCH_PREFIX + row.groupName,
        row.environment,
        row.trigger,
        row.status,
        row.ciEventId,
        row.ciRunId == null || row.ciRunId.isBlank()
            ? List.of()
            : List.of(row.ciRunId.split(",")),
        eu.wohlben.qits.maintenance.bump.CiClient.CONFIG_PATH,
        row.ciRunStatus,
        row.startedAt,
        row.finishedAt,
        row.message,
        BumpService.changes(row));
  }
}
