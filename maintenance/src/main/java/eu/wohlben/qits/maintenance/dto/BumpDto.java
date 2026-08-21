package eu.wohlben.qits.maintenance.dto;

import eu.wohlben.qits.maintenance.pending.Change;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One bump row, with the changes it SENT.
 *
 * <p><b>The changes are what went out, not what is pending now.</b> By the time anyone reads a bump
 * the pins have moved and the latest versions have moved again; a recomputed list would answer a
 * different question from the one the row is being read for.
 *
 * @param id the bump, which is also the ci event's dedupe key
 * @param repository which repository
 * @param group which group, and therefore which branch
 * @param branch the ref the changes go on
 * @param environment which environment's ci ran it
 * @param trigger SCHEDULED or MANUAL
 * @param status REQUESTED, RUNNING, SUCCEEDED, FAILED or NOTHING_TO_DO
 * @param ciEventId the event id the trigger carried
 * @param ciRunIds the runs qits-ci named
 * @param configPath the pipeline file in the wrapper that ran it, which qits-ci records on the run
 * @param ciRunStatus the last ci run status this service read
 * @param startedAt when the row was opened
 * @param finishedAt when it ended, null while it has not
 * @param message the sentence
 * @param changes the payload's changes, verbatim
 */
public record BumpDto(
    UUID id,
    String repository,
    String group,
    String branch,
    String environment,
    String trigger,
    String status,
    String ciEventId,
    List<String> ciRunIds,
    String configPath,
    String ciRunStatus,
    Instant startedAt,
    Instant finishedAt,
    String message,
    List<Change> changes) {}
