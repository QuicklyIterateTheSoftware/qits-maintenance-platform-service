package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;

/**
 * <b>THE KEEP-SET THE ARTIFACT GC READS: every internal registry artifact a catalogued repository's
 * main branch still references, and the freshness of the inventory that says so.</b>
 *
 * <p>It is the third of qits-artifacts' pin sources, behind qits-platform-orchestrator: what the
 * running services deploy, what the images name, and — this one — what the manifests pin. A version
 * named here is a version a build would resolve tomorrow, so deleting it would break a repository
 * nobody has touched.
 *
 * <p><b>Two lists, and the second is the reason the first is served at all.</b> {@code pins} is the
 * answer; {@code repositories} is what the answer is worth. A consumer that keeps only what it is
 * told to keep has no way to tell a repository that pins nothing from one this service could not
 * read this morning — so every row of the inventory is served beside the pins, with the status it
 * carries and the moment it was last read.
 *
 * <p><b>The rows are served AS STORED and are deliberately not folded.</b> Five repositories pinning
 * one library are five rows, each naming its repository and its manifest, because the consumer's own
 * question is "who still holds this" the moment it decides not to delete something. Folding them
 * here would answer a smaller question and lose the only field that makes a keep decision
 * explainable.
 *
 * @param generatedAt when this answer was read out of the store — the moment the two lists below
 *     agree on, not a cached one
 * @param repositories every repository the inventory holds, ordered by name
 * @param pins every internal maven, npm and docker pin, in one deterministic order
 */
public record PinSourceDto(
    Instant generatedAt, List<RepositoryStateDto> repositories, List<ArtifactPinDto> pins) {

  /**
   * One repository's freshness, which is the only thing about it this answer is about.
   *
   * <p>No pin counts and no groups: a consumer reviewing whether the keep-set can be trusted asks
   * when the inventory was last read and whether the read succeeded, and both are here.
   *
   * @param name the catalog name
   * @param status OK, ABSENT, UNREACHABLE or CONFIG_ERROR
   * @param lastScanAt when the last scan of it finished, null when it has never been scanned
   * @param headSha the commit its pins were read at
   */
  public record RepositoryStateDto(
      String name, String status, Instant lastScanAt, String headSha) {}

  /**
   * One pin, as one manifest of one repository wrote it.
   *
   * @param ecosystem maven, npm or docker — never gitlink, whose version is a commit sha rather than
   *     a registry artifact
   * @param name the artifact in its own ecosystem's spelling, which is the registry coordinate
   * @param version the exact version referenced; for npm the LOCK's resolved one, because that is
   *     what an install actually fetches out of the registry
   * @param repository the repository whose manifest holds the line
   * @param manifestPath where that line is, relative to the repository root
   */
  public record ArtifactPinDto(
      String ecosystem, String name, String version, String repository, String manifestPath) {}
}
