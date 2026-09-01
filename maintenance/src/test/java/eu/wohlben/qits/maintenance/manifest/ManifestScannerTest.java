package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.catalog.CatalogEntry;
import eu.wohlben.qits.maintenance.githost.FileLookup;
import eu.wohlben.qits.maintenance.githost.GitHostReader;
import eu.wohlben.qits.maintenance.githost.TreeLookup;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.RepositoryStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The GITLINK half of one repository's scan: {@code .gitmodules} for the name, the TREE for the
 * version.
 *
 * <p>No Quarkus and no network — a git host that answers out of two maps is the whole seam, which
 * is also what makes the one thing worth proving here provable: <b>a gitlink whose sha the host
 * does not report produces no pin at all</b>. The shipped git host is exactly that host today; it
 * collapses every non-directory entry to {@code blob} and has never reported an object name.
 */
class ManifestScannerTest {

  private static final String PROJECT = "qits";
  private static final String REPOSITORY = "qits-artifacts-service";
  private static final String SHA = "3f1a9c0b7d2e4f5a6b8c9d0e1f2a3b4c5d6e7f80";
  private static final String PINNED = "aa11bb22cc33dd44ee55ff6677889900aabbccdd";

  private static final String GITMODULES =
      """
      [submodule "qits-artifacts-frontend"]
      \tpath = service/src/main/webui
      \turl = ../qits-artifacts-frontend.git
      \tignore = all
      """;

  /** A git host holding one revision's trees and blobs, and answering 404 for everything else. */
  private static final class Host extends GitHostReader {

    final Map<String, TreeLookup> trees = new LinkedHashMap<>();
    final Map<String, String> blobs = new LinkedHashMap<>();
    final List<String> read = new ArrayList<>();

    Host() {
      trees.put("", TreeLookup.found(SHA, List.of()));
    }

    Host tree(String path, TreeLookup.TreeEntry... entries) {
      trees.put(path, TreeLookup.found(SHA, List.of(entries)));
      return this;
    }

    Host blob(String path, String content) {
      blobs.put(path, content);
      return this;
    }

    @Override
    public TreeLookup head(String project, String repository, String revision) {
      return tree(project, repository, revision, "");
    }

    @Override
    public TreeLookup tree(String project, String repository, String revision, String path) {
      read.add("tree " + path);
      TreeLookup found = trees.get(path);
      return found == null ? TreeLookup.absent() : found;
    }

    @Override
    public FileLookup blob(String project, String repository, String revision, String path) {
      read.add("blob " + path);
      String content = blobs.get(path);
      return content == null ? FileLookup.absent() : FileLookup.found(content);
    }
  }

  private static ManifestScanner scanner(Host host) {
    ManifestScanner scanner = new ManifestScanner();
    scanner.gitHost = host;
    return scanner;
  }

  private static ManifestScanner.Read read(Host host) {
    return scanner(host).read(new CatalogEntry(PROJECT, REPOSITORY, "main"));
  }

  /** A host that reports the mode and the object name: the submodule is one INTERNAL pin. */
  @Test
  void aSubmoduleWhoseGitlinkShaTheHostReportsIsOnePin() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"), new TreeLookup.TreeEntry("service", "tree"))
            .tree(
                "service/src/main",
                new TreeLookup.TreeEntry("webui", "blob", TreeLookup.TreeEntry.GITLINK_MODE, PINNED))
            .blob(GitmodulesParser.PATH, GITMODULES);

    ManifestScanner.Read read = read(host);

    assertEquals(RepositoryStatus.OK, read.status());
    assertEquals(1, read.pins().size());
    ParsedPin pin = read.pins().get(0);
    assertEquals(Ecosystem.GITLINK, pin.ecosystem());
    assertEquals("qits-artifacts-frontend", pin.name(), "the name is the url's, not the entry's");
    assertEquals(PINNED, pin.version(), "the version of a gitlink is the commit its tree entry records");
    assertEquals("service/src/main/webui", pin.manifestPath());
    assertEquals("gitlink:service/src/main/webui", pin.location());
  }

  /** A host that spells the git object type out instead of the mode says the same thing. */
  @Test
  void anEntryTypedCommitIsAGitlinkToo() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"))
            .tree("service/src/main", new TreeLookup.TreeEntry("webui", "commit", null, PINNED))
            .blob(GitmodulesParser.PATH, GITMODULES);

    assertEquals(1, read(host).pins().size());
  }

  /**
   * THE SHIPPED GIT HOST, and the reason this feature is inert on it: every non-directory entry is
   * a {@code blob} with no mode and no sha, so there is nothing to pin a submodule at. A pin
   * invented here would be compared by the pending rule and then applied by the bump step, into
   * somebody else's repository.
   */
  @Test
  void aHostThatReportsNoShaProducesNoGitlinkPinRatherThanAGuessedOne() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"))
            .tree("service/src/main", new TreeLookup.TreeEntry("webui", "blob"))
            .blob(GitmodulesParser.PATH, GITMODULES);

    assertTrue(read(host).pins().isEmpty());
  }

  /** A section left behind by a commit that removed the submodule names nothing in the tree. */
  @Test
  void aDeclarationTheTreeDoesNotHoldIsSkipped() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"))
            .tree("service/src/main", new TreeLookup.TreeEntry("something-else", "tree"))
            .blob(GitmodulesParser.PATH, GITMODULES);

    assertTrue(read(host).pins().isEmpty());
  }

  /** No file, no reads: a repository without submodules costs nothing. */
  @Test
  void aRepositoryWithNoGitmodulesIsNotAskedForOne() {
    Host host = new Host().tree("", new TreeLookup.TreeEntry("README.md", "blob"));

    assertTrue(read(host).pins().isEmpty());
    assertTrue(
        host.read.stream().noneMatch(call -> call.equals("blob " + GitmodulesParser.PATH)),
        "the root listing already says the file is not there");
  }

  /** Several submodules under one directory cost ONE listing, not one each. */
  @Test
  void submodulesSharingADirectoryShareTheListingThatAnswersForThem() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"))
            .tree(
                "components/qits-ci",
                new TreeLookup.TreeEntry("qits-ci-service", "blob", TreeLookup.TreeEntry.GITLINK_MODE, PINNED),
                new TreeLookup.TreeEntry("qits-ci-frontend", "blob", TreeLookup.TreeEntry.GITLINK_MODE, SHA))
            .blob(
                GitmodulesParser.PATH,
                """
                [submodule "qits-ci-service"]
                \tpath = components/qits-ci/qits-ci-service
                \turl = ../qits-ci-service.git
                [submodule "qits-ci-frontend"]
                \tpath = components/qits-ci/qits-ci-frontend
                \turl = ../qits-ci-frontend.git
                """);

    List<ParsedPin> pins = read(host).pins();

    assertEquals(2, pins.size());
    assertEquals(
        List.of("qits-ci-service", "qits-ci-frontend"), pins.stream().map(ParsedPin::name).toList());
    assertEquals(
        1,
        host.read.stream().filter(call -> call.equals("tree components/qits-ci")).count(),
        "one directory, one listing");
  }

  /** A file git wrote is not this service's configuration: an unreadable one is not CONFIG_ERROR. */
  @Test
  void anUnparseableGitmodulesLeavesTheRepositoryOK() {
    Host host =
        new Host()
            .tree("", new TreeLookup.TreeEntry(".gitmodules", "blob"))
            .blob(GitmodulesParser.PATH, "]]] this is not a config file [[[");

    ManifestScanner.Read read = read(host);

    assertEquals(RepositoryStatus.OK, read.status());
    assertTrue(read.pins().isEmpty());
  }
}
