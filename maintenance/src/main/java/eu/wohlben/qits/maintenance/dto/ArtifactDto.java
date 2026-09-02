package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;

/**
 * One thing this platform publishes, as the internal-libs listing shows it.
 *
 * <p><b>{@code dependentCount} and {@code behindCount} are the two numbers that page exists for.</b>
 * The first says how far a library's reach goes — how many of our own artifacts ship a copy of it —
 * and the second says how much of that reach is stale. A library with fifty dependents all on its
 * newest version is finished work; one with three dependents two versions back is a morning.
 *
 * <p>Both are counted over the NEWEST released version of each dependent, because that is the only
 * one anybody can still do anything about.
 *
 * @param ecosystem maven, npm or docker
 * @param name the artifact, in {@code mt_pin}'s spelling
 * @param repository which repository produced the newest release of it
 * @param latest the newest version any registry reported, from {@code mt_latest}; null when nothing
 *     ever looked it up
 * @param version the newest version this service has a release row for
 * @param occurredAt when that release happened
 * @param sbomStatus how far its bill of materials got — MISSING is ordinary, not a fault
 * @param dependentCount how many distinct artifacts of ours embed it
 * @param behindCount how many of those embed a version older than {@code latest}
 */
public record ArtifactDto(
    String ecosystem,
    String name,
    String repository,
    String latest,
    String version,
    Instant occurredAt,
    String sbomStatus,
    int dependentCount,
    int behindCount) {}
