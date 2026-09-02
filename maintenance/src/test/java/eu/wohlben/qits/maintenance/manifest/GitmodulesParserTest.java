package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@code .gitmodules}, read for two facts: where a submodule sits and which repository it is.
 *
 * <p>The name comes off the URL rather than the section header, and both url spellings this
 * platform uses have to give the same answer — the wrapper writes them relative and the service
 * repositories absolute.
 */
class GitmodulesParserTest {

  @Test
  void aServiceRepositorysWebuiSubmoduleIsReadAsAPathAndARepositoryName() {
    List<GitmodulesParser.Submodule> modules =
        GitmodulesParser.parse(
            """
            [submodule "qits-artifacts-frontend"]
            \tpath = service/src/main/webui
            \turl = https://github.com/QuicklyIterateTheSoftware/qits-artifacts-frontend.git
            \tignore = all
            \tupdate = merge
            \tbranch = main
            """);

    assertEquals(1, modules.size());
    GitmodulesParser.Submodule module = modules.get(0);
    assertEquals("service/src/main/webui", module.path());
    assertEquals("qits-artifacts-frontend", module.name());
    assertEquals("qits-artifacts-frontend", module.entry());
  }

  /** The wrapper's own spelling: relative, resolved against its origin, and the same basename. */
  @Test
  void aRelativeUrlNamesTheSameRepositoryAsAnAbsoluteOne() {
    List<GitmodulesParser.Submodule> modules =
        GitmodulesParser.parse(
            """
            [submodule "qits-ci-service"]
            \tpath = components/qits-ci/qits-ci-service
            \turl = ../qits-ci-service.git
            """);

    assertEquals(1, modules.size());
    assertEquals("qits-ci-service", modules.get(0).name());
    assertEquals("components/qits-ci/qits-ci-service", modules.get(0).path());
  }

  /** A wrapper declares dozens, and each one is a pin of its own. */
  @Test
  void everySubmoduleOfAMultiEntryFileIsReadInFileOrder() {
    List<GitmodulesParser.Submodule> modules =
        GitmodulesParser.parse(
            """
            [submodule "one"]
            \tpath = components/a/one
            \turl = ../one.git
            [submodule "two"]
            \tpath = components/b/two
            \turl = ../two.git
            [submodule "three"]
            \tpath = three
            \turl = ../three.git
            """);

    assertEquals(List.of("one", "two", "three"), modules.stream().map(GitmodulesParser.Submodule::name).toList());
    assertEquals("three", modules.get(2).path(), "a submodule at the repository root has no directory above it");
  }

  /**
   * THE ENTRY NAME IS NOT THE REPOSITORY. A rename leaves the section behind, and the url is what a
   * clone actually resolves — so the url is what a pin is named after.
   */
  @Test
  void theNameComesOffTheUrlAndNotOffTheSectionHeader() {
    List<GitmodulesParser.Submodule> modules =
        GitmodulesParser.parse(
            """
            [submodule "qits-spa-artifacts"]
            \tpath = service/src/main/webui
            \turl = ../qits-artifacts-frontend.git
            """);

    assertEquals("qits-artifacts-frontend", modules.get(0).name());
    assertEquals("qits-spa-artifacts", modules.get(0).entry(), "the old spelling is still what the file says");
  }

  @Test
  void aUrlWithoutTheGitSuffixOrWithATrailingSlashNamesTheSameRepository() {
    assertEquals("qits-docs-frontend", GitmodulesParser.repositoryName("../qits-docs-frontend"));
    assertEquals("qits-docs-frontend", GitmodulesParser.repositoryName("../qits-docs-frontend.git/"));
    assertEquals(
        "qits-docs-frontend",
        GitmodulesParser.repositoryName("git@githost:qits/qits-docs-frontend.git"));
    assertEquals("", GitmodulesParser.repositoryName(null));
  }

  /**
   * TOLERANT, and deliberately so: {@code .gitmodules} is git's file rather than this service's
   * configuration surface, so what parses is used and the rest is dropped.
   */
  @Test
  void abrokenFileYieldsWhateverParsedRatherThanNothingAtAll() {
    List<GitmodulesParser.Submodule> modules =
        GitmodulesParser.parse(
            """
            this line belongs to no section
            [submodule "no-url"]
            \tpath = components/x/no-url
            [submodule "no-path"]
            \turl = ../no-path.git
            [submodule "empty-url"]
            \tpath = components/x/empty
            \turl =
            [not-a-submodule "core"]
            \tpath = whatever
            \turl = ../whatever.git
            [submodule "good"]
            \tpath = components/good/good   # trailing comment
            \turl = ../good.git
            """);

    assertEquals(1, modules.size(), "only the complete submodule section is a pin");
    assertEquals("good", modules.get(0).name());
    assertEquals("components/good/good", modules.get(0).path());
  }

  @Test
  void anEmptyOrAbsentFileIsNoSubmodulesAndNoFailure() {
    assertTrue(GitmodulesParser.parse(null).isEmpty());
    assertTrue(GitmodulesParser.parse("").isEmpty());
    assertTrue(GitmodulesParser.parse("   \n\n# nothing here\n").isEmpty());
  }
}
