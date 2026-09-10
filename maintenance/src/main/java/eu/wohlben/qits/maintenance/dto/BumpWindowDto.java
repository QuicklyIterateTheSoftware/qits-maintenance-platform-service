package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;

/**
 * The dispatch window, as {@code GET /bumps/window} serves it.
 *
 * <p><b>{@code open} is not a stored column</b> — it is {@code closesAt} against the clock, which is
 * the same comparison the tick makes. A row whose window has expired but which no tick has reached
 * yet is a real state and this reports it honestly: {@code closesAt} in the past, {@code open}
 * false.
 *
 * @param openedAt when the cron — or the button — opened it
 * @param closesAt when it ends
 * @param open whether anything would be dispatched right now
 */
public record BumpWindowDto(Instant openedAt, Instant closesAt, boolean open) {}
