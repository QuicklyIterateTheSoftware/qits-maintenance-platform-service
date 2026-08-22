package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Reads one repository's manifests at one revision and turns them into pins.
 *
 * <p><b>The head sha is resolved ONCE and every file is read at it.</b> A scan that read each
 * manifest at {@code main} would produce an inventory of whatever moved while it ran, and the pins
 * of one repository would not correspond to any commit that ever existed.
 *
 * <p><b>Discovery is the repository ROOT plus the reactor.</b> The root listing is read, the
 * manifests in it are taken, and a root pom's declared modules are followed for their poms —
 * transitively, because a module may name modules of its own. Nothing else is walked: a recursive
 * search would read every vendored file in every repository on the platform for a handful of pins.
 *
 * <p><b>{@code service/src/main/webui} is NOT scanned, and the shape of the discovery is what keeps
 * it out.</b> It is a gitlink to the SPA's own repository, which the catalog lists in its own
 * right; scanning it here would double every pin it holds and attribute the copy to the wrong
 * repository. The module walk refuses such a path outright, so a pom that ever named one still
 * cannot pull it in.
 */
@ApplicationScoped
public class ManifestScanner {

  /** The lock beside a root package.json. Without it a manifest has ranges and no versions. */
  public static final String PACKAGE_LOCK = "package-lock.json";

  /** A cap on the reactor walk. A pom that names itself, or a cycle through two modules, would
   * otherwise be an unbounded read against another service. */
  private static final int MAX_POMS = 128;

  private static final Logger LOG = Logger.getLogger(ManifestScanner.class);

  /** The path fragment a gitlink to an embedded SPA sits at on this platform. */
  private static final String EMBEDDED_CLIENT = "src/main/webui";

  /** What PomParser writes as the location of a {@code <parent>} pin. */
  private static final String PARENT_LOCATION = "parent:";

  @Inject GitHostReader gitHost;

  /**
   * What one repository's scan read.
   *
   * @param status the row's status
   * @param headSha the commit every pin was read at, null unless the status is OK or CONFIG_ERROR
   * @param pins every direct pin found, in discovery order
   * @param groups the repository's grouping, empty when the status is CONFIG_ERROR
   * @param message the sentence for the row, null when the status is OK
   */
  public record Read(
      RepositoryStatus status,
      String headSha,
      List<ParsedPin> pins,
      List<GroupConfig.Group> groups,
      eu.wohlben.qits.maintenance.model.GroupSource groupSource,
      String message) {}

  /** Reads one repository. */
  public Read read(CatalogEntry entry) {
    String project = entry.project();
    String name = entry.name();
    String branch = entry.mainBranch() == null || entry.mainBranch().isBlank() ? "main" : entry.mainBranch();

    TreeLookup root = gitHost.head(project, name, branch);
    switch (root.status()) {
      case GONE ->
          // The catalog names it and the git host does not hold this branch. That is the honest
          // ABSENT: there is nothing to scan and nothing is wrong with this service.
          {
            return absent(name, branch);
          }
      case UNREACHABLE, INVALID -> {
        return new Read(
            RepositoryStatus.UNREACHABLE, null, List.of(), List.of(), null, root.message());
      }
      case ABSENT -> {
        return absent(name, branch);
      }
      default -> {
        // FOUND — carry on below.
      }
    }

    String headSha = root.headSha();
    List<ParsedPin> pins = new ArrayList<>();
    pins.addAll(mavenPins(project, name, headSha, root));
    pins.addAll(npmPins(project, name, headSha, root));
    pins.addAll(dockerPins(project, name, headSha, root));
    // ONE ROW PER LINE. Every module of a reactor names the same root property, and each one
    // produced a pin against the root pom above. They are one line and one change; without this
    // the payload would carry the same edit once per module and the commit message would repeat it.
    pins = dedupe(pins);

    GroupConfig.Parsed groups = groups(project, name, headSha);
    if (!groups.ok()) {
      return new Read(
          RepositoryStatus.CONFIG_ERROR, headSha, List.copyOf(pins), List.of(), null, groups.error());
    }
    return new Read(
        RepositoryStatus.OK, headSha, List.copyOf(pins), groups.groups(), groups.source(), null);
  }

  private static Read absent(String name, String branch) {
    return new Read(
        RepositoryStatus.ABSENT,
        null,
        List.of(),
        List.of(),
        null,
        "the git host does not hold " + name + " at " + branch);
  }

  /**
   * The root pom and every pom the reactor reaches from it.
   *
   * <p>The ROOT pom's properties are carried into every module, because a module's version
   * expression is nearly always defined up there — resolving them per-file would drop most of the
   * platform's pins as unresolvable.
   *
   * <p><b>The whole reactor is read BEFORE any of it is parsed</b>, and that ordering is the point:
   * "is this dependency one of our own modules" cannot be answered from a single pom, and it is the
   * question that decides whether a pin is bumpable at all. A one-pass parser reported
   * {@code eu.wohlben.qits:qits-ci-domain} as a dependency of {@code qits-ci} to upgrade, which
   * would have been an offer to overwrite what its own release door stamps.
   */
  private List<ParsedPin> mavenPins(String project, String name, String sha, TreeLookup root) {
    if (!root.hasBlob("pom.xml")) {
      return List.of();
    }
    FileLookup rootPom = gitHost.blob(project, name, sha, "pom.xml");
    if (!rootPom.found()) {
      return List.of();
    }

    // --- pass one: read the reactor ------------------------------------------------------------
    Map<String, String> poms = new LinkedHashMap<>();
    poms.put("pom.xml", rootPom.content());
    Deque<String> pending = new ArrayDeque<>();
    for (String module : PomParser.modules(rootPom.content())) {
      pending.add(modulePom("", module));
    }
    while (!pending.isEmpty() && poms.size() < MAX_POMS) {
      String path = pending.poll();
      if (path == null || poms.containsKey(path)) {
        continue;
      }
      if (path.contains(EMBEDDED_CLIENT)) {
        LOG.debugf("Not following %s of %s: it is an embedded client's own repository", path, name);
        continue;
      }
      FileLookup pom = gitHost.blob(project, name, sha, path);
      if (!pom.found()) {
        continue;
      }
      poms.put(path, pom.content());
      String directory = path.substring(0, path.length() - "pom.xml".length());
      for (String module : PomParser.modules(pom.content())) {
        pending.add(modulePom(directory, module));
      }
    }

    // Every coordinate this repository builds. A dependency on one of these is the repository
    // depending on itself, and a PARENT that is one of these is not a dependency at all.
    Set<String> reactor = new LinkedHashSet<>();
    for (String content : poms.values()) {
      PomParser.coordinate(content).ifPresent(reactor::add);
    }

    // --- pass two: parse, and judge each pin against the reactor -------------------------------
    Map<String, String> rootProperties = PomParser.properties(rootPom.content());
    List<ParsedPin> pins = new ArrayList<>();
    for (Map.Entry<String, String> pom : poms.entrySet()) {
      Map<String, String> inherited = pom.getKey().equals("pom.xml") ? Map.of() : rootProperties;
      for (ParsedPin pin : PomParser.parse(pom.getKey(), pom.getValue(), inherited, "pom.xml")) {
        boolean ownArtifact = reactor.contains(pin.name());
        if (ownArtifact && pin.location().startsWith(PARENT_LOCATION)) {
          // A module inheriting this repository's own root pom is the reactor's shape, not a
          // dependency anybody could upgrade. A parent from OUTSIDE the reactor — a shared
          // qits-parent from the registry — is not in this set and stays a pin.
          continue;
        }
        pins.add(pin.withReactorOwn(pin.reactorOwn() || ownArtifact));
      }
    }
    return pins;
  }

  /** A module entry may name a directory or a pom outright, which is maven's own rule. */
  private static String modulePom(String directory, String module) {
    String value = module.trim();
    while (value.startsWith("./")) {
      value = value.substring(2);
    }
    String path = value.endsWith(".xml") ? value : trimTrailingSlash(value) + "/pom.xml";
    return directory.isEmpty() ? path : directory + path;
  }

  private static String trimTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  /** The root package.json, resolved through the lock beside it. */
  private List<ParsedPin> npmPins(String project, String name, String sha, TreeLookup root) {
    if (!root.hasBlob("package.json")) {
      return List.of();
    }
    FileLookup manifest = gitHost.blob(project, name, sha, "package.json");
    if (!manifest.found()) {
      return List.of();
    }
    String lock = null;
    if (root.hasBlob(PACKAGE_LOCK)) {
      FileLookup lockFile = gitHost.blob(project, name, sha, PACKAGE_LOCK);
      if (lockFile.found()) {
        lock = lockFile.content();
      }
    }
    return NpmParser.parse("package.json", manifest.content(), lock);
  }

  /** Every {@code Dockerfile} and {@code *.Dockerfile} in the repository root. */
  private List<ParsedPin> dockerPins(String project, String name, String sha, TreeLookup root) {
    List<ParsedPin> pins = new ArrayList<>();
    for (TreeLookup.TreeEntry entry : root.entries()) {
      if (!entry.isBlob() || !isDockerfile(entry.name())) {
        continue;
      }
      FileLookup file = gitHost.blob(project, name, sha, entry.name());
      if (file.found()) {
        pins.addAll(DockerParser.parse(entry.name(), file.content()));
      }
    }
    return pins;
  }

  /** One pin per (manifest, name, location) — see the call site for why there are duplicates. */
  private static List<ParsedPin> dedupe(List<ParsedPin> pins) {
    java.util.Map<String, ParsedPin> byLine = new java.util.LinkedHashMap<>();
    for (ParsedPin pin : pins) {
      byLine.putIfAbsent(pin.manifestPath() + " " + pin.name() + " " + pin.location(), pin);
    }
    return new ArrayList<>(byLine.values());
  }

  static boolean isDockerfile(String name) {
    return "Dockerfile".equals(name) || name.endsWith(".Dockerfile");
  }

  /**
   * The repository's grouping.
   *
   * <p>An UNREACHABLE read of the file is treated as absent rather than as a config error: the
   * repository is readable — its manifests were just read at this sha — so a single failing blob is
   * this service's problem and not the repository's. The next scan reads it again.
   */
  private GroupConfig.Parsed groups(String project, String name, String sha) {
    FileLookup file = gitHost.blob(project, name, sha, GroupConfig.PATH);
    if (!file.found()) {
      return GroupConfig.fallback();
    }
    return GroupConfig.parse(file.content());
  }
}
