package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A dependency name is a flat string, and the glob has to treat it as one.
 *
 * <p>A path-aware matcher would stop at the slash in {@code @angular/core} and at the dots and the
 * colon in {@code io.quarkus:quarkus-arc}.
 */
class GlobsTest {

  @Test
  void aScopeGlobMatchesAcrossTheSlash() {
    assertTrue(Globs.matches("@angular/*", "@angular/core"));
    assertTrue(Globs.matches("@angular/*", "@angular/platform-browser"));
    assertFalse(Globs.matches("@angular/*", "@angularjs/core"));
  }

  @Test
  void aMavenGlobMatchesAcrossTheDotsAndTheColon() {
    assertTrue(Globs.matches("io.quarkus:*", "io.quarkus:quarkus-arc"));
    assertFalse(Globs.matches("io.quarkus:*", "io.quarkusio:quarkus-arc"));
  }

  @Test
  void theDotsAreLiteralAndNotWildcards() {
    assertFalse(Globs.matches("io.quarkus:*", "ioXquarkus:quarkus-arc"));
  }

  @Test
  void starAloneClaimsEverything() {
    assertTrue(Globs.matches("*", "anything at all"));
  }

  @Test
  void aQuestionMarkIsExactlyOneCharacter() {
    assertTrue(Globs.matches("qits/build-images/node-base?", "qits/build-images/node-base2"));
    assertFalse(Globs.matches("qits/build-images/node-base?", "qits/build-images/node-base"));
  }

  @Test
  void anyOfTheGlobsIsEnough() {
    assertTrue(Globs.matchesAny(List.of("@angular/*", "@qits/angular"), "@qits/angular"));
    assertFalse(Globs.matchesAny(List.of("@angular/*", "@qits/angular"), "rxjs"));
  }
}
