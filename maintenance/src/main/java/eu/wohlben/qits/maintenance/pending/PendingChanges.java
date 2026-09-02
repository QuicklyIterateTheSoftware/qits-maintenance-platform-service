package eu.wohlben.qits.maintenance.pending;

import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.latest.GitlinkSha;
import eu.wohlben.qits.maintenance.latest.VersionOrder;
import eu.wohlben.qits.maintenance.manifest.Globs;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
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
 * <p><b>A pin has to be actionable before either rule applies.</b> REACTOR and UNRESOLVED pins are
 * recorded and shown and are never pending — there is no line to edit in the first and no version
 * to compare in the second.
 *
 * <p><b>And what is pending is then grouped, by kind or by glob.</b> The fallback grouping is the
 * INTERNAL/EXTERNAL split — a pin's own kind decides its branch — while a repository that declared
 * groups of its own has those tried first, by their globs. See {@link #groupOf}.
 *
 * <p><b>Two rules then decide whether a newer version is OFFERED, and the second is the one people
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
 *
 * <p><b>GITLINK answers neither rule and is decided by {@link #gitlink} instead.</b> Its pin is a
 * commit sha, and there is no order over shas — the two rules above would be arithmetic on a hash.
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
      // Only an ACTIONABLE pin gets this far: `newerVersion` answers empty for REACTOR and
      // UNRESOLVED, so every pin reaching the grouping below is INTERNAL or EXTERNAL and the two
      // kind groups between them claim everything a configured group left.
      Optional<String> group = groupOf(pin, groups);
      if (group.isEmpty()) {
        // Nothing claims it: a repository that declared its own `dependencies` and `external`
        // groups with globs that leave a gap. There is no branch to put this on, so it is not
        // pending — it is unclaimed, and the repository page still shows the newer version.
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
    // A REACTOR or UNRESOLVED pin has no line to bump and no version worth comparing. It is never
    // pending, whatever a registry says — a module depending on its sibling at ${project.version}
    // is the ordinary case, and offering an upgrade for it would be offering to overwrite the
    // release door's own stamp.
    if (!kindOf(pin).actionable()) {
      return Optional.empty();
    }
    MtLatest row = latest.get(key(pin.ecosystem, pin.name));
    if (row == null || row.latest == null || row.latest.isBlank()) {
      return Optional.empty();
    }
    Optional<Ecosystem> ecosystem = Ecosystem.of(pin.ecosystem);
    if (ecosystem.isEmpty()) {
      return Optional.empty();
    }
    if (ecosystem.get() == Ecosystem.GITLINK) {
      return gitlink(pin, row);
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
   * A gitlink's verdict: <b>a difference between two shas, never a comparison of two versions</b>.
   *
   * <p>Neither of this class's two rules can be applied to one, and forcing them would be worse
   * than useless. A gitlink PIN is a commit sha — 40 hex characters that no order ranks, and that
   * maven's order would happily declare "newer" or "older" by reading the leading digits of a hash.
   * And rule 2 has nothing to work on: a sha is neither a release nor a prerelease.
   *
   * <p>So the question is the only one that can be asked honestly — <b>is the submodule pinned at
   * the commit the newest release of that repository was cut from</b> — and both halves have to be
   * KNOWN for the answer to be no. The latest row carries the release's sha in
   * {@code source_url} ({@code latest/GitlinkSha}); a row without one is a release this service
   * could not tie to a commit, and pending must not be guessed from a version alone: the step
   * fetches a tag and would move the gitlink to a commit nothing here compared.
   *
   * <p><b>The change's {@code to} is the calver VERSION and its {@code from} is the sha.</b> They
   * are deliberately not the same kind of thing, because they address different sides: the step
   * fetches {@code refs/tags/<to>} from the sibling repository and writes whatever commit that
   * resolves to, and {@code from} is what the tree holds now — the commit-message half, exactly as
   * it is for every other ecosystem.
   */
  private static Optional<String> gitlink(MtPin pin, MtLatest row) {
    Optional<String> released = GitlinkSha.read(row.sourceUrl);
    if (released.isEmpty() || pin.version == null || pin.version.isBlank()) {
      return Optional.empty();
    }
    if (GitlinkSha.same(pin.version, released.get())) {
      return Optional.empty();
    }
    return Optional.of(row.latest);
  }

  /**
   * The group a pin belongs to.
   *
   * <p><b>Two ways a group claims, and a group uses exactly one of them.</b> A group with a KIND
   * takes every pin of that kind — the two built-in halves, {@code dependencies} for INTERNAL and
   * {@code external} for EXTERNAL. A group with none matches its GLOBS against the dependency name,
   * which is what a repository's own {@code .config/qits/maintenance.yml} declares.
   *
   * <p><b>First match wins</b>, in declaration order, which is what {@code mt_group.ordinal}
   * carries. A configured group is written before the kind pair, so {@code angular} claims
   * {@code @angular/core} and the EXTERNAL half never sees it.
   *
   * <p><b>A REACTOR or UNRESOLVED pin is claimed by no kind group</b>, because neither of those is a
   * kind a group can carry: there is no line to bump and no branch to put it on. It reaches this
   * method only from the detail page — {@link #of} filters it out one step earlier — and a glob
   * group that names it explicitly still claims it, which is what the page then shows.
   */
  public static Optional<String> groupOf(MtPin pin, List<MtGroup> groups) {
    PinKind kind = kindOf(pin);
    for (MtGroup group : groups) {
      Optional<PinKind> claims = kindOf(group);
      boolean matched =
          claims.isPresent()
              ? claims.get() == kind
              : Globs.matchesAny(MaintenanceStore.readStrings(group.patterns), pin.name);
      if (matched) {
        return Optional.of(group.name);
      }
    }
    return Optional.empty();
  }

  /**
   * A group's kind, or empty when it claims by glob.
   *
   * <p>A word this build does not know reads as empty rather than as a crash — the same reading a
   * pin's unknown kind gets, and for the same reason: a row written by a newer build must not take
   * a page down. Such a group then claims by its patterns, which for a kind group are {@code []} —
   * so it claims nothing rather than everything.
   */
  public static Optional<PinKind> kindOf(MtGroup group) {
    if (group.kind == null || group.kind.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(PinKind.valueOf(group.kind));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** A stored pin's kind, defaulting to UNRESOLVED for a word this build does not know. */
  public static PinKind kindOf(MtPin pin) {
    try {
      return PinKind.valueOf(pin.kind);
    } catch (IllegalArgumentException | NullPointerException e) {
      return PinKind.UNRESOLVED;
    }
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
