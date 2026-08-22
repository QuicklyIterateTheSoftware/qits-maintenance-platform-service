package eu.wohlben.qits.maintenance.pending;

import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.manifest.Globs;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.persistence.MaintenanceStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which pins are behind, and which group each belongs to.
 *
 * <p><b>Pending is COMPUTED, never stored.</b> It is {@code mt_pin} joined to {@code mt_latest} and
 * read through {@code mt_group}; a table would be a third copy of a fact two others already carry,
 * going stale between the scan that moves a pin and the scan that moves a latest.
 *
 * <p><b>Two rules decide whether a newer version is OFFERED, and the second is the one people
 * ask about.</b>
 *
 * <ol>
 *   <li>The latest has to be strictly newer in that ecosystem's own order — maven's for a pom, semver
 *       for npm, the same maven order over calver tags for an image.
 *   <li>A PRERELEASE latest is offered only when the pin is a prerelease too. Nobody wants a
 *       release candidate pushed onto a branch by a schedule; somebody already running one does want
 *       the next.
 * </ol>
 *
 * <p><b>A latest that could not be read offers nothing</b>, and that is deliberately different from
 * "up to date". The repository page shows the error beside the pin so the two never look alike.
 */
public final class PendingChanges {

  private PendingChanges() {}

  /**
   * One pending upgrade, with the group that will carry it.
   *
   * @param group the group whose branch this change goes on
   * @param change the edit itself
   */
  public record Pending(String group, Change change) {}

  /**
   * Every pending change of one repository, grouped, in a stable order.
   *
   * @param pins the repository's pins
   * @param latest every latest row, keyed by ecosystem and name
   * @param groups the repository's groups, in declaration order
   */
  public static List<Pending> of(List<MtPin> pins, Map<String, MtLatest> latest, List<MtGroup> groups) {
    List<Pending> pending = new ArrayList<>();
    for (MtPin pin : pins) {
      Optional<String> newer = newerVersion(pin, latest);
      if (newer.isEmpty()) {
        continue;
      }
      Optional<String> group = groupOf(pin.name, groups);
      if (group.isEmpty()) {
        // No group claims it and the repository declared no catch-all. There is no branch to put
        // this on, so it is not pending — it is unclaimed, and the repository page still shows the
        // newer version.
        continue;
      }
      pending.add(
          new Pending(
              group.get(),
              new Change(
                  pin.ecosystem, pin.manifestPath, pin.name, pin.version, newer.get(), pin.location)));
    }
    return List.copyOf(pending);
  }

  /** Only the changes of one group, which is what a bump payload carries. */
  public static List<Change> forGroup(
      List<MtPin> pins, Map<String, MtLatest> latest, List<MtGroup> groups, String group) {
    List<Change> changes = new ArrayList<>();
    for (Pending entry : of(pins, latest, groups)) {
      if (entry.group().equals(group)) {
        changes.add(entry.change());
      }
    }
    return List.copyOf(changes);
  }

  /** How many changes each group has, every declared group present even at zero. */
  public static Map<String, Integer> countByGroup(
      List<MtPin> pins, Map<String, MtLatest> latest, List<MtGroup> groups) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    for (MtGroup group : groups) {
      counts.put(group.name, 0);
    }
    for (Pending entry : of(pins, latest, groups)) {
      counts.merge(entry.group(), 1, Integer::sum);
    }
    return counts;
  }

  /**
   * The version this pin should move to, or empty.
   *
   * <p>Public because the repository detail page reports the same verdict per pin, and a second
   * implementation of "is this one behind" would be the one the UI disagreed with.
   */
  public static Optional<String> newerVersion(MtPin pin, Map<String, MtLatest> latest) {
    MtLatest row = latest.get(key(pin.ecosystem, pin.name));
    if (row == null || row.latest == null || row.latest.isBlank()) {
      return Optional.empty();
    }
    Optional<Ecosystem> ecosystem = Ecosystem.of(pin.ecosystem);
    if (ecosystem.isEmpty()) {
      return Optional.empty();
    }
    if (!VersionOrder.newer(ecosystem.get(), pin.version, row.latest)) {
      return Optional.empty();
    }
    if (VersionOrder.prerelease(ecosystem.get(), row.latest)
        && !VersionOrder.prerelease(ecosystem.get(), pin.version)) {
      return Optional.empty();
    }
    return Optional.of(row.latest);
  }

  /**
   * The group a dependency name belongs to.
   *
   * <p><b>First match wins</b>, in declaration order, which is what {@code mt_group.ordinal}
   * carries. A pin matching both {@code angular} and {@code dependencies} belongs to whichever the
   * author wrote first.
   */
  public static Optional<String> groupOf(String name, List<MtGroup> groups) {
    for (MtGroup group : groups) {
      if (Globs.matchesAny(MaintenanceStore.readStrings(group.patterns), name)) {
        return Optional.of(group.name);
      }
    }
    return Optional.empty();
  }

  /** The join key of {@code mt_pin} and {@code mt_latest}. */
  public static String key(String ecosystem, String name) {
    return ecosystem + " " + name;
  }

  /** Every latest row, keyed for the join. */
  public static Map<String, MtLatest> index(List<MtLatest> rows) {
    Map<String, MtLatest> index = new LinkedHashMap<>();
    for (MtLatest row : rows) {
      index.put(key(row.ecosystem, row.name), row);
    }
    return index;
  }
}
