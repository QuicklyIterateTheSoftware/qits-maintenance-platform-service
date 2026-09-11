package eu.wohlben.qits.maintenance.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * THE HOURS A BRANCH IS NOT WELCOME IN — parsed from one config value, with a typo costing an hour
 * of quiet rather than every bump for ever.
 */
class QuietHoursTest {

  private static Instant at(String time) {
    return LocalDate.of(2026, 9, 11).atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC);
  }

  private static boolean quiet(String configured, String time) {
    return QuietHours.parse(configured).covers(at(time), ZoneOffset.UTC);
  }

  /** The default: nothing configured, so no hour is quiet. A platform that never asked has none. */
  @Test
  void nothingConfiguredIsNoQuietHourAtAll() {
    assertFalse(QuietHours.parse(null).any());
    assertFalse(QuietHours.parse("   ").any());
    assertFalse(quiet(null, "14:00"));
  }

  /** Start inclusive, end exclusive — the plain range. */
  @Test
  void aRangeCoversItsStartAndStopsAtItsEnd() {
    assertFalse(quiet("08:00-18:00", "07:59"));
    assertTrue(quiet("08:00-18:00", "08:00"));
    assertTrue(quiet("08:00-18:00", "17:59"));
    assertFalse(quiet("08:00-18:00", "18:00"));
  }

  /** <b>One range, not two.</b> A quiet night is the obvious thing to configure. */
  @Test
  void aRangeThatWrapsMidnightIsOneRange() {
    assertTrue(quiet("22:00-06:00", "23:30"));
    assertTrue(quiet("22:00-06:00", "01:00"));
    assertFalse(quiet("22:00-06:00", "12:00"));
  }

  /** Several of them, because a lunch break is two ranges and one key. */
  @Test
  void severalRangesAreAllHonoured() {
    assertTrue(quiet("08:00-12:00,13:00-18:00", "09:00"));
    assertFalse(quiet("08:00-12:00,13:00-18:00", "12:30"));
    assertTrue(quiet("08:00-12:00,13:00-18:00", "17:00"));
  }

  /**
   * <b>An empty range is empty, never eternal.</b> Reading "09:00-09:00" as a whole day would stop
   * the estate for good on a value somebody typed meaning nothing.
   */
  @Test
  void aRangeWithTwoEqualEndsCoversNothing() {
    assertFalse(quiet("09:00-09:00", "09:00"));
    assertFalse(quiet("09:00-09:00", "21:00"));
  }

  /**
   * <b>A TYPO IS DROPPED, NOT OBEYED.</b> The two failure modes are not symmetric: read as quiet, an
   * unparseable entry stops every bump for ever and looks exactly like the bug this arming fixes;
   * read as not quiet it costs a branch arriving at an hour somebody dislikes. The entries that did
   * parse still hold.
   */
  @Test
  void anUnparseableEntryIsIgnoredAndTheRestStillHold() {
    assertFalse(QuietHours.parse("nonsense").any());
    assertFalse(quiet("nonsense", "03:00"));
    assertTrue(quiet("nonsense,22:00-23:00", "22:30"));
    assertFalse(quiet("nonsense,22:00-23:00", "12:00"));
  }
}
