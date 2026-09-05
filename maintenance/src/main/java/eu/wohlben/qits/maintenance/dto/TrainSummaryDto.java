package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One release train as a LISTING row — everything a line of the journey index shows, and no nodes.
 *
 * <p><b>The two counts are why this is a record of its own rather than a {@link TrainDto} with an
 * empty list.</b> A row has to show progress — "three of seven have landed" — and the only honest
 * way to say that without shipping every node of every train on the page is to count them on the
 * server. A client folding {@code nodes} itself would need the nodes, and a listing of fifty trains
 * would then carry a few hundred rows nothing on the page renders.
 *
 * @param id the train
 * @param repository the releasing repository, by CATALOG NAME
 * @param version the released version
 * @param status OPEN, COMPLETED or SUPERSEDED. There is no FAILED
 * @param createdAt the RELEASE's moment, off the frame rather than this service's clock — it is
 *     also the key the listing is ordered by, newest first
 * @param completedAt when the journey ended, whichever way; null while it is OPEN. A train with no
 *     expected adopters carries {@code createdAt} here — it arrived the instant it left
 * @param nodeCount how many adopters were expected of this release, decided at the release
 * @param landedCount how many of them have shipped it. Equal to {@code nodeCount} on a COMPLETED
 *     train, and zero on a train nobody has moved on yet
 */
public record TrainSummaryDto(
    UUID id,
    String repository,
    String version,
    String status,
    Instant createdAt,
    Instant completedAt,
    int nodeCount,
    int landedCount) {}
