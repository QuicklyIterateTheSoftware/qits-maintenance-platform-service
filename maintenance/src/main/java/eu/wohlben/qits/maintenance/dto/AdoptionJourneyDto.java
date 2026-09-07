package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;

/**
 * <b>How far one release actually got</b> — the whole journey in one document, folded on this side.
 *
 * <p>The retired release trains answered ONE STATION per request and left the client to stitch a
 * journey out of the child links each station carried. That fold is here now, and it is here
 * because it is cheap: the answer is derived on every read anyway, so folding it costs one walk
 * instead of one request per hop.
 *
 * <p><b>There is no 404.</b> A release this service never heard of publishes no coordinate, so
 * {@link #packages} is empty and every adopter is PENDING — which is the true answer rather than a
 * refusal. The old 404 was a fact about a log that no longer exists.
 *
 * @param repository the releasing repository as this inventory keys it — a CATALOG NAME, whichever
 *     spelling was asked for
 * @param catalogId qits-projects' row id for it, or null when this inventory has no row
 * @param version the released version, as asked
 * @param packages what that release put into a registry. Empty is ordinary: a {@code docs}-only
 *     release names no coordinate and nothing pins a daemon.
 * @param adopters everything downstream, depth ascending then name ascending
 */
public record AdoptionJourneyDto(
    String repository,
    String catalogId,
    String version,
    List<PackageDto> packages,
    List<AdopterDto> adopters) {

  /** One coordinate the release published. It carries no version: every row is at the release's. */
  public record PackageDto(String ecosystem, String name) {}

  /**
   * One downstream repository and what became of the release at it.
   *
   * <p><b>{@code adoptedVersion} is the ADOPTER's OWN release</b>, never the dependency version it
   * took: it is half of the address of the release request that adoption opened
   * ({@code release-requests/by-release/<catalogId>/<adoptedVersion>}), and that resolver matches
   * the adopter's own releases on version.
   *
   * <p><b>PENDING across a gitlink edge may be honest rather than late.</b> A frontend reaches its
   * service as a submodule, which is not a registry coordinate — the evidence for that hop still has
   * to be the service's own SBOM naming the frontend's published bundle. Where the document does not
   * name it, this stays PENDING for ever, and that is a gap in what is published.
   *
   * @param repositoryStatus the consumer's CURRENT inventory status, joined live. {@code ABSENT}
   *     beside a PENDING row says the journey is waiting on something the catalog no longer lists.
   * @param state {@code ADOPTED} or {@code PENDING}, and there is no third
   * @param adoptedAt the publishing moment of that release, off the artifact row
   */
  public record AdopterDto(
      String repository,
      String catalogId,
      String repositoryStatus,
      String archetype,
      int depth,
      List<String> via,
      String state,
      String adoptedVersion,
      Instant adoptedAt) {}
}
