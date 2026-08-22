package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
  void anUnresolvableExpressionIsNotAVersion() {
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
    assertTrue(PomParser.parse("service/pom.xml", module, Map.of(), "pom.xml").isEmpty());
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
