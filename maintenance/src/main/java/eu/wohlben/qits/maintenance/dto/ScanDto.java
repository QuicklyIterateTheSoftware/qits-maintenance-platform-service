package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One scan, as {@code POST /scans} names it and {@code GET /scans/{id}} serves it.
 *
 * @param id the scan, which the 202 answered with
 * @param scope INTERNAL, EXTERNAL or ALL
 * @param repository the one repository it covers, null for the whole catalog
 * @param trigger MANUAL or SCHEDULED
 * @param status REQUESTED, RUNNING, SUCCEEDED or FAILED
 * @param startedAt when it was queued
 * @param finishedAt when it ended, null while it has not
 * @param message the scan in one line, or why it did nothing
 */
public record ScanDto(
    UUID id,
    String scope,
    String repository,
    String trigger,
    String status,
    Instant startedAt,
    Instant finishedAt,
    String message) {}
