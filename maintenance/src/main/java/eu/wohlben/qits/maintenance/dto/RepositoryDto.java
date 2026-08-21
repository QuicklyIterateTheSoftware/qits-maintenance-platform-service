package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;

/**
 * One repository, as the LISTING serves it — no pins, because a list of repositories is read to
 * choose one.
 *
 * @param name the catalog name
 * @param project the project the git host serves it under
 * @param lastScanAt when the last scan finished, null when it has never been scanned
 * @param headSha the commit the pins were read at
 * @param status OK, ABSENT, UNREACHABLE or CONFIG_ERROR
 * @param message why the status is not OK
 * @param pending how many changes are pending across every group
 * @param groups the groups, in declaration order
 */
public record RepositoryDto(
    String name,
    String project,
    Instant lastScanAt,
    String headSha,
    String status,
    String message,
    int pending,
    List<GroupDto> groups) {}
