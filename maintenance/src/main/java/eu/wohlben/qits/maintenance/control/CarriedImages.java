package eu.wohlben.qits.maintenance.control;

import eu.wohlben.qits.maintenance.dto.PinSourceDto;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>THE IMAGE A POM PIN NAMES.</b> Every container image a repository's manifest pins without
 * spelling out — because the version it spells out is a maven or npm artifact whose release put an
 * image of the same calver in the registry.
 *
 * <p><b>Why the keep-set would otherwise have a hole in it.</b> Container image versions used to
 * live in qits-configuration entries, and qits-artifacts read {@code GET /configuration/api/pins} as
 * a pin source. Every consumer has since moved to pinning a MAVEN coordinate whose own version IS
 * the image tag — qits-workspaces at {@code qits-workspace-daemon-protocol} and {@code
 * qits-workspace-editor-image}, qits-projects at {@code qits-projects-daemon-protocol} — so that
 * source now answers {@code {"pins": []}} and holds nothing at all. What is DEPLOYED is still named,
 * by each launching service's own effective-pin door; what lost its cover is the window between a
 * bump landing on main and the consumer deploying, because the bump moves a maven coordinate and
 * this source therefore kept the jar and said nothing about the image of the same version. Measured
 * live 2026-09-17: qits-projects-service pinned {@code qits-projects-daemon-protocol 2026.917.90046}
 * and nothing anywhere named {@code qits/project-agent:2026.917.90046}, whose retention window is
 * {@code P0D}.
 *
 * <p><b>The mapping is the RELEASE's own assertion, never a table.</b> A repository publishing a jar
 * and an image out of one reactor stamps both with the release's version; {@code
 * .config/qits/release.yml} declares them and the {@code SoftwareRelease} frame announces them, which
 * is what filled {@code mt_artifact}. So the rule is a join over two facts this service already
 * holds and nothing anybody has to remember to update:
 *
 * <blockquote>
 * for a pin at {@code (ecosystem, name, version)}, the repository that RELEASED that coordinate, and
 * every DOCKER artifact that same repository released at that same version.
 * </blockquote>
 *
 * <p><b>They join, and they still do not merge.</b> {@code mt_pin} says what a bump edits and {@code
 * mt_artifact} says what a release published; the reading here reaches across {@code (ecosystem,
 * name)} exactly as {@link ArtifactGraph} does everywhere else, and writes through neither. What
 * comes out is a pin row in the sense the consumer means — a version some repository's main branch
 * still references — and {@link PinSourceDto.ArtifactPinDto#via()} names the coordinate it was
 * resolved through, so a keep this produced is never mistaken for a {@code FROM} line somebody wrote.
 *
 * <p><b>A derived row is a DOCKER row in the answer, not a new array.</b> The consumer keeps it under
 * the rule it already has — "referenced by a repository manifest on main" — which is the true
 * sentence: the pom property IS the image tag, and serving what a consumer will actually fetch rather
 * than the literal text of a manifest line is what this source already does for an npm range read
 * through its lock. A seventh keep-set would instead have been a wire change every reader of the
 * supplied pin document has to land before this one can ship, for a keep that is not a different
 * kind of fact.
 *
 * <p><b>No artifact row, no keep, and that is honest rather than defensive.</b> Nothing asserted an
 * image for a version this service never saw released, and inventing one would put a name in a
 * keep-set that no release ever carried. The window that matters is the freshly released version,
 * whose row {@code SoftwareReleaseListener} writes off the frame whatever becomes of its SBOM.
 */
@ApplicationScoped
public class CarriedImages {

  @Inject ArtifactGraph graph;

  /**
   * The docker rows to serve BESIDE the stored pins, derived from them.
   *
   * @param pins the stored pin rows, already filtered to INTERNAL and to a known ecosystem — which
   *     is what makes the producer lookup below meaningful: an external coordinate is somebody
   *     else's release and carries none of our images
   * @return the derived rows, in no particular order; the caller sorts them into the one total order
   *     the answer is served in
   */
  public List<PinSourceDto.ArtifactPinDto> resolve(List<PinSourceDto.ArtifactPinDto> pins) {
    if (pins == null || pins.isEmpty()) {
      return List.of();
    }
    // Which repository released each coordinate anybody pins, read once. A carrier that names no
    // release of ours — a coordinate this platform has never published — falls out here.
    Map<String, String> producers = graph.producers();
    Map<String, Set<String>> versionsByRepository = new LinkedHashMap<>();
    for (PinSourceDto.ArtifactPinDto pin : pins) {
      String repository = carrierOf(producers, pin);
      if (repository != null) {
        versionsByRepository
            .computeIfAbsent(repository, name -> new LinkedHashSet<>())
            .add(pin.version());
      }
    }

    Map<String, Map<String, List<String>>> images = graph.imagesReleasedWith(versionsByRepository);
    if (images.isEmpty()) {
      return List.of();
    }

    // Keyed so one image reached through two carriers of the same release — a pom pinning both
    // halves of one repository's reactor — is one row rather than two saying the same thing.
    Map<String, PinSourceDto.ArtifactPinDto> derived = new LinkedHashMap<>();
    for (PinSourceDto.ArtifactPinDto pin : pins) {
      String repository = carrierOf(producers, pin);
      if (repository == null) {
        continue;
      }
      for (String image :
          images.getOrDefault(repository, Map.of()).getOrDefault(pin.version(), List.of())) {
        derived.putIfAbsent(
            key(image, pin.version(), pin.repository(), pin.manifestPath()),
            new PinSourceDto.ArtifactPinDto(
                Ecosystem.DOCKER.wireName(),
                image,
                pin.version(),
                // The PINNING repository and the manifest that holds the line, never the one that
                // released the image: the consumer's question the moment it decides not to delete
                // something is "who still holds this", and the answer is the pom with the property
                // in it. Where the NAME came from is `via`.
                pin.repository(),
                pin.manifestPath(),
                pin.ecosystem() + " " + pin.name()));
      }
    }

    // A manifest that also writes the image out — a Dockerfile `FROM` at the same version — has
    // already said this, with a line a bump can edit. The stored row is the better one of the two.
    for (PinSourceDto.ArtifactPinDto pin : pins) {
      derived.remove(key(pin.name(), pin.version(), pin.repository(), pin.manifestPath()));
    }
    return List.copyOf(new ArrayList<>(derived.values()));
  }

  /**
   * The repository whose release published this pin's coordinate, or null when it carries no image
   * of ours.
   *
   * <p>DOCKER and GITLINK are refused at the door rather than filtered later: an image is its own
   * carrier and resolving one would restate the row it came from, and a gitlink's version is a
   * commit sha, which no release ever stamped an artifact with.
   */
  private static String carrierOf(
      Map<String, String> producers, PinSourceDto.ArtifactPinDto pin) {
    Ecosystem ecosystem = Ecosystem.of(pin.ecosystem()).orElse(null);
    if (ecosystem != Ecosystem.MAVEN && ecosystem != Ecosystem.NPM) {
      return null;
    }
    String repository = producers.get(ArtifactGraph.producerKey(pin.ecosystem(), pin.name()));
    return repository == null || repository.isBlank() ? null : repository;
  }

  private static String key(String name, String version, String repository, String manifestPath) {
    return name + ":" + version + " " + repository + " " + manifestPath;
  }
}
