package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ONE release train in full: the station, what it released, and every adopter it is owed.
 *
 * <p><b>One train, never a stitched journey.</b> A node's {@code childTrainId} names the train the
 * adoption produced, and following those links is the CLIENT's job by design — the API answers one
 * station per request. Two reasons: a merged journey has no natural size (a library release reaches
 * the whole estate two hops out) and no natural root (the same train is a child of one journey and
 * the head of another), so a server-side fold would have to invent both. A view that walks the links
 * can draw exactly the depth it renders and cache each station on its own.
 *
 * @param id the train
 * @param repository the releasing repository, by CATALOG NAME
 * @param version the released version
 * @param status OPEN, COMPLETED or SUPERSEDED. There is no FAILED — a journey that stalls is one
 *     nobody finished, not one that failed
 * @param createdAt the RELEASE's moment, off the frame rather than this service's clock
 * @param completedAt when the journey ended, whichever way; null while it is OPEN
 * @param supersededBy which train made this one irrelevant — a later release of the same repository
 *     — or null unless the status is SUPERSEDED
 * @param packages what this release put into a registry, which is what labels the station. <b>Empty
 *     is an ordinary answer</b>: a {@code docs}-only release names no coordinate at all, and a
 *     daemon release names nothing any manifest pins
 * @param nodes every adopter expected of this release, decided AT the release and never recomputed
 */
public record TrainDto(
    UUID id,
    String repository,
    String version,
    String status,
    Instant createdAt,
    Instant completedAt,
    UUID supersededBy,
    List<PackageDto> packages,
    List<TrainNodeDto> nodes) {

  /**
   * One coordinate the release published.
   *
   * <p><b>No version field, and that is not an omission.</b> These are the {@code mt_artifact} rows
   * of this train's own {@code (repository, version)}, so every one of them is at {@link
   * TrainDto#version} and a second copy of it per row would be a field that can only ever agree.
   *
   * @param ecosystem maven, npm or docker. GITLINK never appears: a gitlink is banked in bulk by the
   *     wrapper's own release and is never a train's concern
   * @param name the package, spelled as {@code mt_pin} spells it — which is what makes it the same
   *     string the adopting side's pin carries
   */
  public record PackageDto(String ecosystem, String name) {}
}
