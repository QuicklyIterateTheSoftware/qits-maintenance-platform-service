package eu.wohlben.qits.maintenance.latest;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * npm's version order: semver 2.0.0, and only what a registry actually publishes.
 *
 * <p><b>npm does not use maven's order and the difference is not cosmetic.</b> Semver compares the
 * three numbers, then ranks a version WITH a prerelease BELOW the same version without one, then
 * compares the prerelease identifiers dot-part by dot-part, numeric parts numerically and the rest
 * as strings. Maven's {@code ComparableVersion} ranks {@code 1.0.0-rc.1} above {@code 1.0.0-beta}
 * for a different reason and disagrees outright on {@code 1.0.0-1} against {@code 1.0.0-alpha}.
 * Ranking a published npm version with maven's rules would offer upgrades that go backwards.
 *
 * <p><b>Build metadata is ignored</b>, as the specification says: {@code 1.0.0+a} and
 * {@code 1.0.0+b} are the same version and neither is newer.
 *
 * <p>A string this cannot parse is not a version. A registry that publishes one is not lying — npm
 * accepted looser versions long ago — but there is no order over it, so it is left out of the
 * comparison rather than guessed at.
 */
public record SemVer(int major, int minor, int patch, String prerelease) implements Comparable<SemVer> {

  private static final Pattern PATTERN =
      Pattern.compile(
          "^v?(?<major>0|[1-9]\\d*)\\.(?<minor>0|[1-9]\\d*)\\.(?<patch>0|[1-9]\\d*)"
              + "(?:-(?<prerelease>(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)"
              + "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
              + "(?:\\+(?<build>[0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

  /** The version, or null when the string is not semver. */
  public static SemVer parse(String value) {
    if (value == null) {
      return null;
    }
    Matcher matcher = PATTERN.matcher(value.trim());
    if (!matcher.matches()) {
      return null;
    }
    return new SemVer(
        Integer.parseInt(matcher.group("major")),
        Integer.parseInt(matcher.group("minor")),
        Integer.parseInt(matcher.group("patch")),
        matcher.group("prerelease"));
  }

  /** Whether the version carries a prerelease part. */
  public boolean prereleaseVersion() {
    return prerelease != null;
  }

  @Override
  public int compareTo(SemVer other) {
    int result = Integer.compare(major, other.major);
    if (result != 0) {
      return result;
    }
    result = Integer.compare(minor, other.minor);
    if (result != 0) {
      return result;
    }
    result = Integer.compare(patch, other.patch);
    if (result != 0) {
      return result;
    }
    if (prerelease == null && other.prerelease == null) {
      return 0;
    }
    // A version with no prerelease outranks the same numbers with one.
    if (prerelease == null) {
      return 1;
    }
    if (other.prerelease == null) {
      return -1;
    }
    return comparePrerelease(prerelease, other.prerelease);
  }

  private static int comparePrerelease(String left, String right) {
    String[] ours = left.split("\\.");
    String[] theirs = right.split("\\.");
    int shared = Math.min(ours.length, theirs.length);
    for (int i = 0; i < shared; i++) {
      int result = compareIdentifier(ours[i], theirs[i]);
      if (result != 0) {
        return result;
      }
    }
    // More identifiers wins when everything shared is equal: 1.0.0-alpha < 1.0.0-alpha.1.
    return Integer.compare(ours.length, theirs.length);
  }

  private static int compareIdentifier(String left, String right) {
    boolean leftNumeric = isNumeric(left);
    boolean rightNumeric = isNumeric(right);
    if (leftNumeric && rightNumeric) {
      return new java.math.BigInteger(left).compareTo(new java.math.BigInteger(right));
    }
    // Numeric identifiers always rank below alphanumeric ones.
    if (leftNumeric) {
      return -1;
    }
    if (rightNumeric) {
      return 1;
    }
    return left.compareTo(right);
  }

  private static boolean isNumeric(String value) {
    if (value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (!Character.isDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }
}
