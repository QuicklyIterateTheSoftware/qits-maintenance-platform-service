package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.model.GroupSource;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A repository's own grouping, and what happens when it is not one. */
class GroupConfigTest {

  @Test
  void noFileIsOneCatchAllGroup() {
    GroupConfig.Parsed parsed = GroupConfig.fallback();
    assertTrue(parsed.ok());
    assertEquals(GroupSource.DEFAULT, parsed.source());
    assertEquals(List.of(GroupConfig.DEFAULT_GROUP), parsed.groups().stream().map(GroupConfig.Group::name).toList());
  }

  @Test
  void declarationOrderIsKeptBecauseFirstMatchWins() {
    GroupConfig.Parsed parsed =
        GroupConfig.parse(
            """
            groups:
              - name: angular
                deps: ["@angular/*", "@qits/angular"]
              - name: quarkus
                deps: ["io.quarkus:*", "io.quarkus.platform:*"]
            """);
    assertTrue(parsed.ok());
    assertEquals(GroupSource.CONFIG, parsed.source());
    assertEquals(
        List.of("angular", "quarkus", GroupConfig.DEFAULT_GROUP),
        parsed.groups().stream().map(GroupConfig.Group::name).toList());
    assertEquals(List.of("@angular/*", "@qits/angular"), parsed.groups().get(0).patterns());
  }

  @Test
  void theCatchAllIsAppendedLastSoNoConfiguredGroupLosesAPin() {
    GroupConfig.Parsed parsed =
        GroupConfig.parse("groups:\n  - name: angular\n    deps: [\"@angular/*\"]\n");
    GroupConfig.Group last = parsed.groups().get(parsed.groups().size() - 1);
    assertEquals(GroupConfig.DEFAULT_GROUP, last.name());
    assertEquals(List.of(GroupConfig.MATCH_ALL), last.patterns());
  }

  @Test
  void aRepositoryThatDeclaresTheDefaultGroupItselfKeepsItsOwnPatterns() {
    GroupConfig.Parsed parsed =
        GroupConfig.parse("groups:\n  - name: dependencies\n    deps: [\"io.quarkus:*\"]\n");
    assertEquals(1, parsed.groups().size());
    assertEquals(List.of("io.quarkus:*"), parsed.groups().get(0).patterns());
  }

  @Test
  void anEmptyFileSaysWhatAnAbsentOneSays() {
    assertEquals(GroupSource.DEFAULT, GroupConfig.parse("").source());
    assertEquals(GroupSource.DEFAULT, GroupConfig.parse("# only a comment\n").source());
  }

  @Test
  void brokenYamlIsAConfigErrorWithASentence() {
    GroupConfig.Parsed parsed = GroupConfig.parse("groups: [ - unbalanced\n");
    assertFalse(parsed.ok());
    assertTrue(parsed.error().contains(GroupConfig.PATH));
  }

  @Test
  void aGroupNameThatCouldNotBeABranchIsRefused() {
    assertFalse(GroupConfig.parse("groups:\n  - name: has/slash\n    deps: [\"*\"]\n").ok());
    assertFalse(GroupConfig.parse("groups:\n  - name: \"\"\n    deps: [\"*\"]\n").ok());
  }

  @Test
  void aGroupWithNoDepsClaimsNothingAndIsRefused() {
    assertFalse(GroupConfig.parse("groups:\n  - name: angular\n    deps: []\n").ok());
    assertFalse(GroupConfig.parse("groups:\n  - name: angular\n").ok());
  }

  @Test
  void oneNameTwiceIsRefusedRatherThanSilentlyMerged() {
    assertFalse(
        GroupConfig.parse(
                "groups:\n  - name: a\n    deps: [\"x\"]\n  - name: a\n    deps: [\"y\"]\n")
            .ok());
  }
}
