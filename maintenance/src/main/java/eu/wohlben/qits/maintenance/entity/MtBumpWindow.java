package eu.wohlben.qits.maintenance.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The open dispatch window, or no row at all.
 *
 * <p><b>It is a row and not a field because this service redeploys itself mid-window.</b> The
 * nightly cron opens a window and a 15s tick hands out one bump at a time while it is open; the
 * bump of {@code qits-maintenance-platform-service} is one of the bumps it hands out, and that
 * bump's release replaces this container. Held in memory, the window died with it — measured live
 * on 2026-09-10, nineteen bumps dispatched cleanly from 06:32 and then nothing at all from 08:11,
 * with eleven repositories owed and four hours of window left. See {@code V10__bump_window.sql} for
 * the whole reasoning; the short form is that a restart mid-window is this design's ordinary
 * outcome rather than its rare accident.
 *
 * <p><b>One row, keyed by a name.</b> {@link #INTERNAL} is the only window there is, so opening is
 * an upsert on that key and two opens cannot become two windows.
 */
@Entity
@Table(name = "mt_bump_window")
public class MtBumpWindow extends PanacheEntityBase {

  /** The one window: the nightly INTERNAL group dispatch. */
  public static final String INTERNAL = "internal";

  @Id
  @Column(length = 32)
  public String id;

  /** When the cron opened it. Read by people, never by the gate. */
  @Column(name = "opened_at", nullable = false)
  public Instant openedAt;

  /** When it ends. The one fact the gate reads. */
  @Column(name = "closes_at", nullable = false)
  public Instant closesAt;
}
