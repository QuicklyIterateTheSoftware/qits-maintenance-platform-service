package eu.wohlben.qits.maintenance.latest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Semver 2.0.0 itself — npm's order, which maven's rules disagree with. */
class SemVerTest {

  @Test
  void buildMetadataDoesNotChangeTheVersion() {
    assertEquals(0, SemVer.parse("1.0.0+a").compareTo(SemVer.parse("1.0.0+b")));
  }

  @Test
  void aNumericIdentifierRanksBelowAnAlphanumericOne() {
    assertTrue(SemVer.parse("1.0.0-1").compareTo(SemVer.parse("1.0.0-alpha")) < 0);
  }

  @Test
  void aLongVersionStringIsStillCompared() {
    assertTrue(SemVer.parse("1.0.0-alpha.99999999999999999999")
            .compareTo(SemVer.parse("1.0.0-alpha.1")) > 0);
  }

  @Test
  void whatIsNotSemverDoesNotParse() {
    assertNull(SemVer.parse("2026.821.110104.1"));
    assertNull(SemVer.parse("latest"));
    assertNotNull(SemVer.parse("v1.2.3"));
  }

  @Test
  void aPrereleaseSaysSo() {
    assertTrue(SemVer.parse("1.0.0-rc.1").prereleaseVersion());
    assertTrue(!SemVer.parse("1.0.0").prereleaseVersion());
  }
}
