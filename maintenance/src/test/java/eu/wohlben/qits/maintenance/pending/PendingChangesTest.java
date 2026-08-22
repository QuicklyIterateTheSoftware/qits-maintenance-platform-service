package eu.wohlben.qits.maintenance.pending;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The join of pins and latest versions, read through the groups.
 *
 * <p>No database: the computation reads fields off three row shapes and writes nothing, which is
 * exactly why it is worth having as a pure function.
 */
class PendingChangesTest {

  private static MtPin pin(Ecosystem ecosystem, String name, String version, String manifest) {
    MtPin pin = new MtPin();
    pin.repository = "qits-ci";
    pin.manifestPath = manifest;
    pin.ecosystem = ecosystem.wireName();
    pin.name = name;
    pin.version = version;
    pin.kind = "INTERNAL";
    pin.location = "dependency:" + name;
    return pin;
  }

  private static MtLatest latest(Ecosystem ecosystem, String name, String version) {
    MtLatest row = new MtLatest();
    row.ecosystem = ecosystem.wireName();
    row.name = name;
    row.latest = version;
    row.checkedAt = Instant.now();
    return row;
  }

  private static MtGroup group(String name, int ordinal, String... patterns) {
    MtGroup group = new MtGroup();
    group.name = name;
    group.ordinal = ordinal;
    group.patterns = "[" + String.join(",", java.util.Arrays.stream(patterns).map(p -> "\"" + p + "\"").toList()) + "]";
    group.source = "CONFIG";
    return group;
  }

  private static final List<MtGroup> GROUPS =
      List.of(group("angular", 0, "@angular/*"), group("dependencies", 1, "*"));

  @Test
  void aNewerVersionIsPendingAndCarriesTheFileAndTheLocation() {
    MtPin pin = pin(Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream", "2026.811.1", "pom.xml");
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, "eu.wohlben.qits:qits-eventstream", "2026.821.3")));

    List<PendingChanges.Pending> pending = PendingChanges.of(List.of(pin), latest, GROUPS);
    assertEquals(1, pending.size());
    Change change = pending.get(0).change();
    assertEquals("dependencies", pending.get(0).group());
    assertEquals("maven", change.ecosystem());
    assertEquals("pom.xml", change.manifestPath());
    assertEquals("2026.811.1", change.from());
    assertEquals("2026.821.3", change.to());
    assertEquals("dependency:eu.wohlben.qits:qits-eventstream", change.location());
  }

  @Test
  void anUpToDatePinIsNotPending() {
    MtPin pin = pin(Ecosystem.MAVEN, "g:a", "1.2.3", "pom.xml");
    Map<String, MtLatest> latest =
        PendingChanges.index(List.of(latest(Ecosystem.MAVEN, "g:a", "1.2.3")));
    assertTrue(PendingChanges.of(List.of(pin), latest, GROUPS).isEmpty());
  }

  @Test
  void aPrereleaseIsNotOfferedToAReleasedPin() {
    MtPin pin = pin(Ecosystem.NPM, "@angular/core", "21.0.4", "package.json");
    Map<String, MtLatest> latest =
        PendingChanges.index(List.of(latest(Ecosystem.NPM, "@angular/core", "22.0.0-rc.1")));
    assertTrue(PendingChanges.of(List.of(pin), latest, GROUPS).isEmpty());
  }

  @Test
  void aPrereleaseIsOfferedWhenThePinIsOneToo() {
    MtPin pin = pin(Ecosystem.NPM, "@angular/core", "22.0.0-rc.1", "package.json");
    Map<String, MtLatest> latest =
        PendingChanges.index(List.of(latest(Ecosystem.NPM, "@angular/core", "22.0.0-rc.2")));
    List<PendingChanges.Pending> pending = PendingChanges.of(List.of(pin), latest, GROUPS);
    assertEquals(1, pending.size());
    assertEquals("angular", pending.get(0).group());
  }

  @Test
  void aLatestThatCouldNotBeReadOffersNothingAndIsNotUpToDate() {
    MtLatest row = latest(Ecosystem.MAVEN, "g:a", null);
    row.error = "the registry could not be reached";
    MtPin pin = pin(Ecosystem.MAVEN, "g:a", "1.0.0", "pom.xml");
    assertTrue(PendingChanges.of(List.of(pin), PendingChanges.index(List.of(row)), GROUPS).isEmpty());
    assertFalse(PendingChanges.newerVersion(pin, PendingChanges.index(List.of(row))).isPresent());
  }

  @Test
  void thisRepositorysOwnArtifactIsNeverPendingWhateverTheRegistrySays() {
    // qits-ci depending on qits-ci-domain at ${project.version}. The registry HAS a newer release —
    // this repository published it — and offering it would be offering to overwrite what the
    // release door stamps on the next build.
    MtPin pin = pin(Ecosystem.MAVEN, "eu.wohlben.qits:qits-ci-domain", "2026.821.1", "pom.xml");
    pin.kind = "REACTOR";
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, "eu.wohlben.qits:qits-ci-domain", "2026.822.9")));
    assertTrue(PendingChanges.of(List.of(pin), latest, GROUPS).isEmpty());
    assertTrue(PendingChanges.newerVersion(pin, latest).isEmpty());
  }

  @Test
  void aPinStillCarryingAnExpressionIsNeverPending() {
    MtPin pin = pin(Ecosystem.MAVEN, "${project.groupId}:qits-arch-rules", "1.0.0", "pom.xml");
    pin.kind = "UNRESOLVED";
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(latest(Ecosystem.MAVEN, "${project.groupId}:qits-arch-rules", "2.0.0")));
    assertTrue(PendingChanges.of(List.of(pin), latest, GROUPS).isEmpty());
  }

  @Test
  void aKindThisBuildDoesNotKnowIsReadAsUnresolvedRatherThanCrashing() {
    // A row written by a newer build, read by an older one. Refusing to act on it is the safe
    // reading; throwing would take the whole page down.
    MtPin pin = pin(Ecosystem.MAVEN, "g:a", "1.0.0", "pom.xml");
    pin.kind = "SOMETHING_LATER";
    assertEquals(
        eu.wohlben.qits.maintenance.model.PinKind.UNRESOLVED, PendingChanges.kindOf(pin));
  }

  @Test
  void firstMatchWinsInDeclarationOrder() {
    assertEquals("angular", PendingChanges.groupOf("@angular/core", GROUPS).orElseThrow());
    assertEquals("dependencies", PendingChanges.groupOf("rxjs", GROUPS).orElseThrow());
  }

  @Test
  void aPinNoGroupClaimsHasNoBranchAndIsNotPending() {
    List<MtGroup> onlyAngular = List.of(group("angular", 0, "@angular/*"));
    MtPin pin = pin(Ecosystem.NPM, "rxjs", "7.0.0", "package.json");
    Map<String, MtLatest> latest =
        PendingChanges.index(List.of(latest(Ecosystem.NPM, "rxjs", "8.0.0")));
    assertTrue(PendingChanges.of(List.of(pin), latest, onlyAngular).isEmpty());
    assertTrue(PendingChanges.groupOf("rxjs", onlyAngular).isEmpty());
  }

  @Test
  void everyDeclaredGroupIsCountedEvenAtZero() {
    Map<String, Integer> counts = PendingChanges.countByGroup(List.of(), Map.of(), GROUPS);
    assertEquals(Map.of("angular", 0, "dependencies", 0), counts);
  }

  @Test
  void oneGroupsChangesAreOnlyItsOwn() {
    List<MtPin> pins =
        List.of(
            pin(Ecosystem.NPM, "@angular/core", "21.0.0", "package.json"),
            pin(Ecosystem.NPM, "rxjs", "7.0.0", "package.json"));
    Map<String, MtLatest> latest =
        PendingChanges.index(
            List.of(
                latest(Ecosystem.NPM, "@angular/core", "21.1.0"),
                latest(Ecosystem.NPM, "rxjs", "7.1.0")));
    List<Change> angular = PendingChanges.forGroup(pins, latest, GROUPS, "angular");
    assertEquals(1, angular.size());
    assertEquals("@angular/core", angular.get(0).name());
  }
}
