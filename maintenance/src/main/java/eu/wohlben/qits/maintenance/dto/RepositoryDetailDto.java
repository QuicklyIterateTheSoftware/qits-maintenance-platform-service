package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;

/**
 * One repository with every pin it holds — what {@code GET /repositories/{name}} serves.
 *
 * <p>The repository's own fields are {@link RepositoryDto}'s, repeated rather than nested: a client
 * rendering a page should not have to reach through a wrapper for the status.
 *
 * <p><b>Two lists, and they are two different facts.</b> {@code pins} is what this repository's
 * manifests DECLARE — every one of them has a line, a location and a group, and a bump can move it.
 * {@code transitives} is what its released artifacts CONTAIN and no manifest names: read out of the
 * newest ingested bill of materials of each artifact the repository publishes, with anything that
 * is also a pin removed. Neither is derivable from the other, they never merge, and a page showing
 * only the first cannot answer the question an advisory raises.
 *
 * <p>{@code transitives} is empty for a repository whose artifacts have no SBOM stored — the
 * ordinary state during the rollout — and that reads as "we do not know", not as "there are none".
 */
public record RepositoryDetailDto(
    String name,
    String project,
    Instant lastScanAt,
    String headSha,
    String status,
    String message,
    int pending,
    List<GroupDto> groups,
    List<PinDto> pins,
    List<TransitiveDto> transitives) {}
