package eu.wohlben.qits.maintenance.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A FROM line's only address is its line number; an ARG's is the argument's name. */
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

  /**
   * The shape the three ARG-pinned repositories are written in: a default that a
   * {@code docker build --build-arg} can override, and a FROM that resolves it. Everything else on
   * this file is what a real one carries beside the base image — a download url, a plain version, a
   * port, and the bare re-declaration that brings the argument into the second stage.
   */
  private static final String ARG_DOCKERFILE =
      """
      ARG OPENVSCODE_VERSION=1.109.5
      ARG OPENVSCODE_URL=https://github.com/gitpod-io/openvscode-server/releases/download/x.tar.gz
      ARG PORT=8080:3000
      ARG WORKSPACE_IMAGE=registry.dev.localhost:8080/qits/workspace:2026.902.181302
      FROM ${WORKSPACE_IMAGE}
      ARG BASE=qits/workspace-base:2026.902.143920
      ARG DIGESTED=qits/workspace-base@sha256:0123456789abcdef
      ARG TAGLESS=qits/workspace-base
      ARG DERIVED=${WORKSPACE_IMAGE}
      ARG WORKSPACE_IMAGE
      """;

  private static Optional<ParsedPin> find(List<ParsedPin> pins, String name) {
    return pins.stream().filter(pin -> pin.name().equals(name)).findFirst();
  }

  /** An ARG pin is addressed by its argument's name, so that is what the assertions look it up by. */
  private static Optional<ParsedPin> at(List<ParsedPin> pins, String location) {
    return pins.stream().filter(pin -> pin.location().equals(location)).findFirst();
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
    // The host is then dropped from the NAME, which is a different rule and the next test's.
    ParsedPin builder = at(DockerParser.parse("Dockerfile", DOCKERFILE), "line:2").orElseThrow();
    assertEquals("jdk-25", builder.version());
    assertEquals("quay/quarkus/ubi9-quarkus-mandrel-builder-image", builder.name());
  }

  /**
   * ONE IMAGE IS ONE ROW, whether the reference that named it carried a registry or not. The host is
   * an address rather than part of the name — the same image is {@code registry.dev.localhost:8080/
   * qits/workspace} to a {@code docker build} and {@code qits/workspace} to everything else on this
   * platform, including the {@code SoftwareRelease} that moves its latest.
   */
  @Test
  void aRegistryHostIsAnAddressAndNotPartOfTheName() {
    List<ParsedPin> pins = DockerParser.parse("Dockerfile", ARG_DOCKERFILE);

    assertEquals("qits/workspace", at(pins, "arg:WORKSPACE_IMAGE").orElseThrow().name());
    // A namespace is not a host, so a two-segment name keeps both of its segments.
    assertEquals("qits/workspace-base", at(pins, "arg:BASE").orElseThrow().name());
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

  // --- ARG: the pin a FROM resolves from ------------------------------------------------------

  /**
   * THE ARGUMENT'S NAME IS THE ADDRESS, and that is the whole reason this location exists: the line
   * a base image is pinned on moves whenever anything above it is edited, and {@code
   * WORKSPACE_IMAGE} does not.
   */
  @Test
  void anArgDefaultIsPinnedAndLocatedByItsArgumentName() {
    List<ParsedPin> pins = DockerParser.parse("Dockerfile", ARG_DOCKERFILE);

    ParsedPin workspace = at(pins, "arg:WORKSPACE_IMAGE").orElseThrow();
    assertEquals("qits/workspace", workspace.name());
    assertEquals("2026.902.181302", workspace.version());
    assertEquals("Dockerfile", workspace.manifestPath());

    ParsedPin base = at(pins, "arg:BASE").orElseThrow();
    assertEquals("qits/workspace-base", base.name());
    assertEquals("2026.902.143920", base.version());
  }

  /**
   * The exclusions a FROM already has, on a line that says much less. A digest has no order, a
   * tagless reference means {@code :latest}, and an argument whose default is another argument
   * resolves at build time.
   */
  @Test
  void anArgIsHeldToEverythingAFromIs() {
    List<ParsedPin> pins = DockerParser.parse("Dockerfile", ARG_DOCKERFILE);

    assertTrue(at(pins, "arg:DIGESTED").isEmpty(), "a digest is a content address");
    assertTrue(at(pins, "arg:TAGLESS").isEmpty(), "a tagless reference is a moving one");
    assertTrue(at(pins, "arg:DERIVED").isEmpty(), "an expression resolves at build time");
  }

  /**
   * AND TO TWO MORE, because a Dockerfile's arguments are not all images. A url's scheme carries a
   * colon that is not a tag, and a value with no {@code /} in its image half is a version or a port
   * — every one of these is a real line of the three repositories this reads.
   */
  @Test
  void anArgThatIsNotAnImageReferenceIsNotAPin() {
    List<ParsedPin> pins = DockerParser.parse("Dockerfile", ARG_DOCKERFILE);

    assertTrue(at(pins, "arg:OPENVSCODE_URL").isEmpty(), "a download url is not an image");
    assertTrue(at(pins, "arg:OPENVSCODE_VERSION").isEmpty(), "a plain version names no image");
    assertTrue(at(pins, "arg:PORT").isEmpty(), "8080:3000 is not an image and a tag");
  }

  /** An {@code ARG NAME} with no default declares an argument; there is nothing pinned to read. */
  @Test
  void aRedeclaredArgumentCarriesNoDefaultAndIsNotASecondPin() {
    assertEquals(
        1,
        DockerParser.parse("Dockerfile", ARG_DOCKERFILE).stream()
            .filter(pin -> pin.location().equals("arg:WORKSPACE_IMAGE"))
            .count());
  }
}
