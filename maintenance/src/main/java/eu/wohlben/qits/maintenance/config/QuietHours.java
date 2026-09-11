package eu.wohlben.qits.maintenance.config;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * THE HOURS IN WHICH A BRANCH IS NOT WELCOME — said out loud, instead of implied by a cron.
 *
 * <p>Dispatch used to be armed by the 02:00 cron, which meant the hour was doing two jobs at once:
 * deciding that the night's work may start, and deciding that it may only start then. The second
 * one is a real policy — somebody may genuinely not want a {@code maintenance/dependencies} branch
 * arriving in the middle of their afternoon — but it was never stated anywhere a reader could find
 * it, and it cost the first job entirely: debt created at 10:44 waited for a timestamp with an idle
 * CI queue in front of it.
 *
 * <p>So the two are split. {@code BumpDispatcher} arms itself on debt, and this is the only thing
 * that says "not now": {@code qits.maintenance.bump.dispatch.quiet-hours}, read in {@code
 * qits.maintenance.time-zone}, <b>empty by default</b> — a platform that never asked for a quiet
 * period does not silently have one.
 *
 * <h2>The format</h2>
 *
 * <p>Comma-separated {@code HH:MM-HH:MM} ranges, start inclusive and end exclusive: {@code
 * 08:00-18:00} is the working day, {@code 22:00-06:00} wraps midnight and is one range rather than
 * two, and {@code 08:00-12:00,13:00-18:00} is a lunch break. A range whose two ends are equal is
 * empty rather than eternal — "from nine to nine" as a whole day is a thing nobody types by
 * accident, and reading it that way would stop the estate for good.
 *
 * <h2>A typo is dropped with a WARN, not obeyed</h2>
 *
 * <p>The two failure modes are not symmetric. An unparseable entry read as "quiet" stops every bump
 * for ever and looks exactly like the bug this whole ticket is about; read as "not quiet" it costs
 * at most a branch arriving at an hour somebody dislikes. So a bad entry is dropped, the WARN names
 * it, and the entries that did parse still hold.
 */
public final class QuietHours {

  private static final Logger LOG = Logger.getLogger(QuietHours.class);

  /** No quiet period at all: every hour is one a bump may be dispatched in. */
  public static final QuietHours NONE = new QuietHours(List.of(), "");

  /**
   * One span of the clock, both ends local times.
   *
   * @param from inclusive
   * @param to exclusive; {@code to} before {@code from} wraps midnight
   */
  private record Span(LocalTime from, LocalTime to) {

    boolean covers(LocalTime at) {
      if (from.equals(to)) {
        return false;
      }
      if (from.isBefore(to)) {
        return !at.isBefore(from) && at.isBefore(to);
      }
      return !at.isBefore(from) || at.isBefore(to);
    }
  }

  private final List<Span> spans;

  private final String spelled;

  private QuietHours(List<Span> spans, String spelled) {
    this.spans = spans;
    this.spelled = spelled;
  }

  /** Reads the config value. Null, blank and "every entry was a typo" all come back {@link #NONE}. */
  public static QuietHours parse(String configured) {
    if (configured == null || configured.isBlank()) {
      return NONE;
    }
    List<Span> spans = new ArrayList<>();
    for (String entry : configured.split(",")) {
      String one = entry.trim();
      if (one.isEmpty()) {
        continue;
      }
      Span span = span(one);
      if (span == null) {
        LOG.warnf(
            "qits.maintenance.bump.dispatch.quiet-hours entry `%s` is not HH:MM-HH:MM and is"
                + " ignored; bumps are dispatched in those hours.",
            one);
        continue;
      }
      spans.add(span);
    }
    return spans.isEmpty() ? NONE : new QuietHours(List.copyOf(spans), configured.trim());
  }

  private static Span span(String entry) {
    int dash = entry.indexOf('-');
    if (dash < 0) {
      return null;
    }
    try {
      LocalTime from = LocalTime.parse(entry.substring(0, dash).trim());
      LocalTime to = LocalTime.parse(entry.substring(dash + 1).trim());
      return new Span(from, to);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Whether this instant falls in a quiet period, read in the configured zone. */
  public boolean covers(Instant now, ZoneId zone) {
    if (spans.isEmpty()) {
      return false;
    }
    LocalTime at = now.atZone(zone).toLocalTime();
    for (Span span : spans) {
      if (span.covers(at)) {
        return true;
      }
    }
    return false;
  }

  /** Whether anything is configured at all — the ordinary case is that nothing is. */
  public boolean any() {
    return !spans.isEmpty();
  }

  /** The configured value, for the sentence a gate gives when it declines. */
  @Override
  public String toString() {
    return spelled;
  }
}
