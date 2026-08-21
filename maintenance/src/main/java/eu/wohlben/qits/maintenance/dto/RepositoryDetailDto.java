package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;

/**
 * One repository with every pin it holds — what {@code GET /repositories/{name}} serves.
 *
 * <p>The repository's own fields are {@link RepositoryDto}'s, repeated rather than nested: a client
 * rendering a page should not have to reach through a wrapper for the status.
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
    List<PinDto> pins) {}
