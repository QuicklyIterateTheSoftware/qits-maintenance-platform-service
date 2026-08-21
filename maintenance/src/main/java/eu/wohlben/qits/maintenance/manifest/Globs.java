package eu.wohlben.qits.maintenance.manifest;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The glob a group's {@code deps} entry is, and the one {@code GET /dependencies?name=} takes.
 *
 * <p><b>Two wildcards and nothing else</b>: {@code *} for any run of characters and {@code ?} for
 * one. No character classes, no alternation, no path semantics — a dependency name is a flat
 * string, and {@code @angular/*} has to match {@code @angular/core} exactly as
 * {@code io.quarkus:*} matches {@code io.quarkus:quarkus-arc}. A glob library with path semantics
 * would stop at the slash in the first and match nothing.
 *
 * <p><b>Everything else is quoted.</b> A maven name is full of dots and a colon, and an npm scope
 * starts with an at-sign; unquoted, {@code io.quarkus:*} would match {@code ioXquarkus:anything}.
 */
public final class Globs {

  private Globs() {}

  /** Whether the value matches the glob, case-sensitively — a package name is case-sensitive. */
  public static boolean matches(String glob, String value) {
    if (glob == null || value == null) {
      return false;
    }
    return compile(glob).matcher(value).matches();
  }

  /** Whether the value matches any of the globs. */
  public static boolean matchesAny(List<String> globs, String value) {
    if (globs == null) {
      return false;
    }
    for (String glob : globs) {
      if (matches(glob, value)) {
        return true;
      }
    }
    return false;
  }

  /** The glob as a regular expression. Public so a caller matching many values compiles once. */
  public static Pattern compile(String glob) {
    StringBuilder regex = new StringBuilder(glob.length() + 8);
    for (int i = 0; i < glob.length(); i++) {
      char character = glob.charAt(i);
      switch (character) {
        case '*' -> regex.append(".*");
        case '?' -> regex.append('.');
        default -> regex.append(Pattern.quote(String.valueOf(character)));
      }
    }
    return Pattern.compile(regex.toString());
  }
}
