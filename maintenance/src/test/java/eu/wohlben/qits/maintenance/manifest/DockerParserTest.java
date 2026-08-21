package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A FROM line's only address is its line number. */
class DockerParserTest {

  private static final String DOCKERFILE =
      """
      # a comment
      FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
      FROM --platform=linux/amd64 qits/build-images/maven-base:2026.813.1
      FROM qits/build-images/node-base@sha256:0123456789abcdef
      FROM qits/build-images/ci-base
      FROM qits/build-images/${TAGGED}:latest
      from qits/build-images/lower:1.2.3
      """;

  private static Optional<ParsedPin> find(List<ParsedPin> pins, String name) {
    return pins.stream().filter(pin -> pin.name().equals(name)).findFirst();
  }

  @Test
  void aFromLineIsLocatedByItsLineNumber() {
    ParsedPin maven =
        find(DockerParser.parse("Dockerfile", DOCKERFILE), "qits/build-images/maven-base")
            .orElseThrow();
    assertEquals("2026.813.1", maven.version());
    assertEquals("line:3", maven.location());
    assertEquals("Dockerfile", maven.manifestPath());
  }

  @Test
  void aColonInTheRegistryHostIsNotATag() {
    // mirror.dev.localhost:8080/... — reading that colon as a tag would name an image nobody wrote.
    ParsedPin builder =
        find(
                DockerParser.parse("Dockerfile", DOCKERFILE),
                "mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image")
            .orElseThrow();
    assertEquals("jdk-25", builder.version());
  }

  @Test
  void aDigestIsAContentAddressAndHasNoOrder() {
    assertTrue(find(DockerParser.parse("Dockerfile", DOCKERFILE), "qits/build-images/node-base").isEmpty());
  }

  @Test
  void aTaglessFromMeansLatestAndThereIsNothingToCompare() {
    assertTrue(find(DockerParser.parse("Dockerfile", DOCKERFILE), "qits/build-images/ci-base").isEmpty());
  }

  @Test
  void aBuildArgResolvesAtBuildTimeAndNotHere() {
    assertTrue(
        DockerParser.parse("Dockerfile", DOCKERFILE).stream()
            .noneMatch(pin -> pin.name().contains("$")));
  }

  @Test
  void theVerbIsCaseInsensitive() {
    assertEquals(
        "1.2.3",
        find(DockerParser.parse("Dockerfile", DOCKERFILE), "qits/build-images/lower")
            .orElseThrow()
            .version());
  }
}
