package eu.wohlben.qits.maintenance.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code .gitmodules} — every submodule a repository declares, as a path and a repository name.
 *
 * <p><b>It is git's config format, not yaml and not ini-with-opinions.</b> A section header names
 * the module's ENTRY name, which is not necessarily the repository's: the wrapper's own convention
 * pins them together but nothing in git enforces it, and several repositories on this platform carry
 * an entry renamed after the repository it points at. So the name this parser reports is read off
 * the URL — the last segment with any {@code .git} suffix removed — because a url is what a clone
 * actually resolves, and it is the only field that has to be right for the submodule to work at all.
 *
 * <p><b>Both url spellings on this platform produce the same name.</b> The wrapper writes them
 * relative ({@code ../qits-ci-service.git}, resolved against its own origin) and the service
 * repositories write an absolute GitHub url; the basename rule reads either.
 *
 * <p><b>Nothing here throws, and a file that will not parse is not a repository's fault.</b>
 * {@code .gitmodules} is git's file rather than this service's configuration surface — a repository
 * whose grouping config is broken is CONFIG_ERROR because it configured this service wrongly, and
 * that argument does not reach a file git wrote. So an unreadable line is skipped, an entry missing
 * either half is skipped, and what parsed is returned.
 */
public final class GitmodulesParser {

  /** Where the file is, always: git reads it from the repository root and nowhere else. */
  public static final String PATH = ".gitmodules";

  private GitmodulesParser() {}

  /**
   * One submodule declaration.
   *
   * @param entry the {@code [submodule "<entry>"]} name — carried for a log line, never a pin's
   * @param path where the gitlink sits, repository-relative
   * @param url what a clone resolves, verbatim
   * @param name the repository the url names: its last segment, without {@code .git}
   */
  public record Submodule(String entry, String path, String url, String name) {}

  /**
   * Every submodule the file declares, in file order, skipping anything incomplete.
   *
   * <p>An entry with no {@code path}, no {@code url} or a url whose basename is empty is dropped:
   * the first has no gitlink to pin, and the other two have no name to pin it under. A name is what
   * joins a pin to {@code mt_latest}, so guessing one would join a row to the wrong dependency.
   */
  public static List<Submodule> parse(String content) {
    List<Submodule> modules = new ArrayList<>();
    if (content == null || content.isBlank()) {
      return modules;
    }
    String entry = null;
    Map<String, String> values = new LinkedHashMap<>();
    for (String raw : content.split("\r?\n")) {
      String line = strip(raw);
      if (line.isEmpty()) {
        continue;
      }
      if (line.charAt(0) == '[') {
        add(modules, entry, values);
        entry = section(line);
        values = new LinkedHashMap<>();
        continue;
      }
      if (entry == null) {
        // A key outside any section, or inside one this parser is not in — git would refuse the
        // file; here it is one line nobody can act on.
        continue;
      }
      int equals = line.indexOf('=');
      if (equals <= 0) {
        continue;
      }
      String key = line.substring(0, equals).trim().toLowerCase(Locale.ROOT);
      String value = unquote(line.substring(equals + 1).trim());
      if (!value.isEmpty()) {
        values.putIfAbsent(key, value);
      }
    }
    add(modules, entry, values);
    return List.copyOf(modules);
  }

  /**
   * The repository a submodule url names.
   *
   * <p>The last path segment with any {@code .git} suffix and any trailing slash removed. Public
   * because the bump step derives a sibling clone url from exactly this name, and a second reading
   * of it would be the one that disagreed.
   */
  public static String repositoryName(String url) {
    if (url == null) {
      return "";
    }
    String value = url.trim();
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    int slash = value.lastIndexOf('/');
    if (slash >= 0) {
      value = value.substring(slash + 1);
    }
    // A scp-style url — git@host:group/repo.git — has no slash before the repository when the
    // group is absent, and the colon is then the separator.
    int colon = value.lastIndexOf(':');
    if (colon >= 0) {
      value = value.substring(colon + 1);
    }
    if (value.toLowerCase(Locale.ROOT).endsWith(".git")) {
      value = value.substring(0, value.length() - ".git".length());
    }
    return value.trim();
  }

  private static void add(List<Submodule> modules, String entry, Map<String, String> values) {
    if (entry == null) {
      return;
    }
    String path = normalisePath(values.get("path"));
    String url = values.get("url");
    String name = repositoryName(url);
    if (path.isEmpty() || url == null || name.isEmpty()) {
      return;
    }
    modules.add(new Submodule(entry, path, url.trim(), name));
  }

  /** A leading {@code ./} and a trailing slash are spellings of the same path; a pin needs one. */
  private static String normalisePath(String value) {
    if (value == null) {
      return "";
    }
    String path = value.trim();
    while (path.startsWith("./")) {
      path = path.substring(2);
    }
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  /** The subsection name of {@code [submodule "name"]}, or null for any other section. */
  private static String section(String line) {
    int close = line.lastIndexOf(']');
    String body = (close > 0 ? line.substring(1, close) : line.substring(1)).trim();
    int quote = body.indexOf('"');
    String head = (quote < 0 ? body : body.substring(0, quote)).trim();
    if (!"submodule".equalsIgnoreCase(head)) {
      return null;
    }
    if (quote < 0) {
      return "";
    }
    int end = body.lastIndexOf('"');
    return end > quote ? body.substring(quote + 1, end) : "";
  }

  /** Everything before an unquoted {@code #} or {@code ;}, trimmed. */
  private static String strip(String line) {
    boolean quoted = false;
    for (int index = 0; index < line.length(); index++) {
      char character = line.charAt(index);
      if (character == '"') {
        quoted = !quoted;
      } else if (!quoted && (character == '#' || character == ';')) {
        return line.substring(0, index).trim();
      }
    }
    return line.trim();
  }

  private static String unquote(String value) {
    if (value.length() > 1 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
      return value.substring(1, value.length() - 1).trim();
    }
    return value;
  }
}
