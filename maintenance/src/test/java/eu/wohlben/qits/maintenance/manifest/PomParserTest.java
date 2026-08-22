package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What a pom is read for: the version, and WHERE it is set.
 *
 * <p>The location is the half that matters most — the bump step edits by it, so a wrong one is a
 * wrong edit in somebody else's repository.
 */
class PomParserTest {

  private static final String ROOT =
      """
      <project>
        <groupId>eu.wohlben.qits</groupId>
        <artifactId>example</artifactId>
        <version>2026.821.1</version>
        <properties>
          <qits.eventstream.version>2026.811.1</qits.eventstream.version>
          <quarkus.platform.version>3.34.6</quarkus.platform.version>
        </properties>
        <modules>
          <module>domain</module>
          <module>service</module>
        </modules>
        <dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>io.quarkus.platform</groupId>
              <artifactId>quarkus-bom</artifactId>
              <version>${quarkus.platform.version}</version>
            </dependency>
          </dependencies>
        </dependencyManagement>
        <dependencies>
          <dependency>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>qits-eventstream</artifactId>
            <version>${qits.eventstream.version}</version>
          </dependency>
          <dependency>
            <groupId>org.apache.maven</groupId>
            <artifactId>maven-artifact</artifactId>
            <version>3.9.12</version>
          </dependency>
          <dependency>
            <groupId>io.quarkus</groupId>
            <artifactId>quarkus-arc</artifactId>
          </dependency>
        </dependencies>
      </project>
      """;

  private static Optional<ParsedPin> find(List<ParsedPin> pins, String name) {
    return pins.stream().filter(pin -> pin.name().equals(name)).findFirst();
  }

  @Test
  void aPropertyBecomesThePinsLocationRatherThanTheDependencyElement() {
    List<ParsedPin> pins = PomParser.parse("pom.xml", ROOT, Map.of(), "pom.xml");
    ParsedPin eventstream = find(pins, "eu.wohlben.qits:qits-eventstream").orElseThrow();
    assertEquals("2026.811.1", eventstream.version());
    assertEquals("property:qits.eventstream.version", eventstream.location());
    assertEquals(Ecosystem.MAVEN, eventstream.ecosystem());
    assertEquals("pom.xml", eventstream.manifestPath());
  }

  @Test
  void aLiteralVersionIsLocatedAtItsDependency() {
    List<ParsedPin> pins = PomParser.parse("pom.xml", ROOT, Map.of(), "pom.xml");
    ParsedPin artifact = find(pins, "org.apache.maven:maven-artifact").orElseThrow();
    assertEquals("3.9.12", artifact.version());
    assertEquals("dependency:org.apache.maven:maven-artifact", artifact.location());
  }

  @Test
  void dependencyManagementIsReadToo() {
    List<ParsedPin> pins = PomParser.parse("pom.xml", ROOT, Map.of(), "pom.xml");
    ParsedPin bom = find(pins, "io.quarkus.platform:quarkus-bom").orElseThrow();
    assertEquals("3.34.6", bom.version());
    assertEquals("property:quarkus.platform.version", bom.location());
  }

  @Test
  void aDependencyWithNoVersionIsNotAPinBecauseThereIsNoLineToEdit() {
    List<ParsedPin> pins = PomParser.parse("pom.xml", ROOT, Map.of(), "pom.xml");
    assertTrue(find(pins, "io.quarkus:quarkus-arc").isEmpty());
  }

  @Test
  void theParentIsAPinWithItsOwnLocation() {
    String module =
        """
        <project>
          <parent>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>example</artifactId>
            <version>2026.821.1</version>
          </parent>
          <artifactId>service</artifactId>
        </project>
        """;
    List<ParsedPin> pins = PomParser.parse("service/pom.xml", module, Map.of(), "pom.xml");
    ParsedPin parent = find(pins, "eu.wohlben.qits:example").orElseThrow();
    assertEquals("parent:eu.wohlben.qits:example", parent.location());
    assertEquals("service/pom.xml", parent.manifestPath());
  }

  @Test
  void aModuleResolvingARootPropertyIsRecordedAgainstTheRootPom() {
    // The line the bump step edits is the property, and the property is in the root pom. Recording
    // the module would send the step to a file that does not hold the value.
    String module =
        """
        <project>
          <artifactId>service</artifactId>
          <dependencies>
            <dependency>
              <groupId>eu.wohlben.qits</groupId>
              <artifactId>qits-eventstream</artifactId>
              <version>${qits.eventstream.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;
    Map<String, String> rootProperties = PomParser.properties(ROOT);
    List<ParsedPin> pins = PomParser.parse("service/pom.xml", module, rootProperties, "pom.xml");
    ParsedPin pin = find(pins, "eu.wohlben.qits:qits-eventstream").orElseThrow();
    assertEquals("pom.xml", pin.manifestPath());
    assertEquals("property:qits.eventstream.version", pin.location());
    assertEquals("2026.811.1", pin.version());
  }

  @Test
  void anUnresolvableExpressionIsRECORDEDRatherThanDroppedOrGuessedAt() {
    // It is visible on the repository page, and PinKind reads it as UNRESOLVED so that nothing is
    // ever asked of a registry about it. Dropping it would hide what a repository actually wrote.
    String module =
        """
        <project>
          <artifactId>service</artifactId>
          <dependencies>
            <dependency>
              <groupId>x</groupId><artifactId>y</artifactId><version>${nobody.set.this}</version>
            </dependency>
          </dependencies>
        </project>
        """;
    ParsedPin pin = PomParser.parse("service/pom.xml", module, Map.of(), "pom.xml").get(0);
    assertEquals("${nobody.set.this}", pin.version());
    assertTrue(pin.unresolved());
  }

  /**
   * THE POM THAT KILLED THE FIRST LIVE SCAN. Every reactor on the platform writes its own modules
   * this way, and before the built-ins were resolved the groupId reached a URL builder verbatim:
   * {@code .../central/${project/groupId}/qits-artifacts-artifacts/maven-metadata.xml}.
   */
  @Test
  void theReactorsOwnModuleResolvesFromTheBuiltInCoordinatesAndIsNeverBumpable() {
    String reactor =
        """
        <project>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>qits-artifacts</artifactId>
          <version>2026.821.1</version>
          <dependencies>
            <dependency>
              <groupId>${project.groupId}</groupId>
              <artifactId>qits-artifacts-artifacts</artifactId>
              <version>${project.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;
    ParsedPin pin = PomParser.parse("pom.xml", reactor, Map.of(), "pom.xml").get(0);
    assertEquals("eu.wohlben.qits:qits-artifacts-artifacts", pin.name());
    assertEquals("2026.821.1", pin.version());
    assertFalse(pin.unresolved());
    // The version came from the pom's own coordinates, so there is no line to bump and no
    // `property:` location to send a step to.
    assertTrue(pin.reactorOwn());
    assertEquals("dependency:eu.wohlben.qits:qits-artifacts-artifacts", pin.location());
  }

  @Test
  void aModuleWithNoCoordinatesOfItsOwnInheritsTheParentsForTheBuiltIns() {
    String module =
        """
        <project>
          <parent>
            <groupId>eu.wohlben.qits</groupId>
            <artifactId>qits-ci</artifactId>
            <version>2026.821.1</version>
          </parent>
          <artifactId>qits-ci-service</artifactId>
          <dependencies>
            <dependency>
              <groupId>${project.groupId}</groupId>
              <artifactId>qits-ci-domain</artifactId>
              <version>${project.version}</version>
            </dependency>
          </dependencies>
        </project>
        """;
    List<ParsedPin> pins = PomParser.parse("service/pom.xml", module, Map.of(), "pom.xml");
    ParsedPin domain = find(pins, "eu.wohlben.qits:qits-ci-domain").orElseThrow();
    assertEquals("2026.821.1", domain.version());
    assertTrue(domain.reactorOwn());
  }

  @Test
  void anExpressionInTheGroupIdAndTheArtifactIdIsResolvedFromPropertiesToo() {
    // io.quarkus.platform:quarkus-bom, written as two properties and a third for the version — the
    // shape every qits pom uses for the BOM import.
    String pom =
        """
        <project>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>example</artifactId>
          <version>1</version>
          <properties>
            <quarkus.platform.group-id>io.quarkus.platform</quarkus.platform.group-id>
            <quarkus.platform.artifact-id>quarkus-bom</quarkus.platform.artifact-id>
            <quarkus.platform.version>3.34.6</quarkus.platform.version>
          </properties>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>${quarkus.platform.group-id}</groupId>
                <artifactId>${quarkus.platform.artifact-id}</artifactId>
                <version>${quarkus.platform.version}</version>
              </dependency>
            </dependencies>
          </dependencyManagement>
        </project>
        """;
    ParsedPin bom =
        find(PomParser.parse("pom.xml", pom, Map.of(), "pom.xml"), "io.quarkus.platform:quarkus-bom")
            .orElseThrow();
    assertEquals("3.34.6", bom.version());
    assertFalse(bom.reactorOwn());
    // The version IS a declared property, so it keeps its editable location.
    assertEquals("property:quarkus.platform.version", bom.location());
  }

  @Test
  void theDeprecatedSpellingsResolveToo() {
    String pom =
        """
        <project>
          <groupId>eu.wohlben.qits</groupId>
          <artifactId>legacy</artifactId>
          <version>9.9.9</version>
          <dependencies>
            <dependency>
              <groupId>${pom.groupId}</groupId><artifactId>a</artifactId><version>${version}</version>
            </dependency>
          </dependencies>
        </project>
        """;
    ParsedPin pin = PomParser.parse("pom.xml", pom, Map.of(), "pom.xml").get(0);
    assertEquals("eu.wohlben.qits:a", pin.name());
    assertEquals("9.9.9", pin.version());
    assertTrue(pin.reactorOwn());
  }

  @Test
  void aPropertyThatNamesItselfIsLeftAloneRatherThanUnrolled() {
    String pom =
        """
        <project>
          <artifactId>loop</artifactId>
          <properties><a.version>${a.version}</a.version></properties>
          <dependencies>
            <dependency><groupId>g</groupId><artifactId>a</artifactId><version>${a.version}</version></dependency>
          </dependencies>
        </project>
        """;
    ParsedPin pin = PomParser.parse("pom.xml", pom, Map.of(), "pom.xml").get(0);
    assertTrue(pin.unresolved());
  }

  @Test
  void theReactorModulesAreTheOnesTheRootNames() {
    assertEquals(List.of("domain", "service"), PomParser.modules(ROOT));
  }

  @Test
  void aDocumentThatDoesNotParseYieldsNoPins() {
    assertTrue(PomParser.parse("pom.xml", "<project", Map.of(), "pom.xml").isEmpty());
  }
}
