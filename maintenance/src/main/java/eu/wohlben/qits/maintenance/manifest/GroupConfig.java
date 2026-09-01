package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.maintenance.model.GroupSource;
import eu.wohlben.qits.maintenance.model.PinKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A repository's own grouping: which pins travel together on which branch.
 *
 * <p><b>The fallback is the INTERNAL/EXTERNAL split.</b> A repository that carries no
 * {@code .config/qits/maintenance.yml} — which is every repository on the platform today — gets two
 * groups rather than one catch-all: {@code dependencies} claims every INTERNAL pin and
 * {@code external} claims every EXTERNAL one, so the platform's own releases travel on
 * {@code maintenance/dependencies} and everybody else's on {@code maintenance/external}. The two
 * halves move on different schedules and are reviewed by different eyes; one branch carrying both
 * made a nightly internal bump wait behind somebody's opinion about a framework major.
 *
 * <p><b>A kind group claims by KIND; the glob mechanism remains.</b> {@link Group#kind()} set means
 * the group takes every actionable pin of that kind and its patterns are empty; {@link Group#kind()}
 * null means the globs decide, exactly as before. The glob mechanism is not deprecated by the split
 * — it is how a repository asks for a FINER grouping than the two built-in halves, and a configured
 * group is always tried before the pair.
 *
 * <p><b>The file is {@code .config/qits/maintenance.yml} and it is optional.</b> The kind pair is
 * appended after whatever it declares, so a configured repository's unclaimed pins still split by
 * kind rather than falling off the end.
 *
 * <p><b>An invalid file is a CONFIG_ERROR on the repository row and nothing is bumped for it.</b>
 * The alternative — falling back to the default grouping — would put changes on a branch the author
 * explicitly configured against, quietly, and the mistake would only surface as a surprising
 * commit.
 *
 * <p><b>Declaration order is part of the meaning.</b> A pin matching two groups belongs to the
 * first, so the order survives into {@code mt_group.ordinal} and out again.
 */
public final class GroupConfig {

  /** The path a scan reads it from, in every repository. */
  public static final String PATH = ".config/qits/maintenance.yml";

  /**
   * The INTERNAL half of the fallback. The name is unchanged from when it was the one catch-all
   * group: {@code maintenance/dependencies} is a branch the release door already cleans and three
   * repositories already name, and renaming it would have been a cutover nobody asked for.
   */
  public static final String DEFAULT_GROUP = "dependencies";

  /** The EXTERNAL half of the fallback, on {@code maintenance/external}. */
  public static final String EXTERNAL_GROUP = "external";

  /** A group's name reaches a branch name and a shell, so it is held to what a ref may carry. */
  private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  private GroupConfig() {}

  /**
   * One group: a name, and either a kind or a set of globs to claim pins with.
   *
   * @param name the group, which is also the branch suffix
   * @param patterns the globs on a dependency name — empty for a kind group
   * @param kind the pin kind this group claims, or null when the patterns decide
   */
  public record Group(String name, List<String> patterns, PinKind kind) {

    /** A group that claims what its globs match — what a repository's own file declares. */
    public static Group glob(String name, List<String> patterns) {
      return new Group(name, List.copyOf(patterns), null);
    }

    /** A group that claims every pin of one kind, and carries no globs at all. */
    public static Group ofKind(String name, PinKind kind) {
      return new Group(name, List.of(), kind);
    }
  }

  /**
   * The parse of one file, or the reason it is not usable.
   *
   * @param groups the groups in declaration order
   * @param source whether they came from the file or from the fallback
   * @param error the sentence for the repository row, or null
   */
  public record Parsed(List<Group> groups, GroupSource source, String error) {

    public boolean ok() {
      return error == null;
    }
  }

  /** What a repository with no {@code .config/qits/maintenance.yml} gets: the split. */
  public static Parsed fallback() {
    return new Parsed(kindTail(), GroupSource.DEFAULT, null);
  }

  /**
   * The two kind groups, in the order they claim: INTERNAL first.
   *
   * <p>The order is not cosmetic. A pin is claimed by the first group whose rule matches, and a pin
   * has exactly one kind — so the pair is unambiguous whichever way round it is written, and this
   * order is the one an operator reads on the page: our own releases, then everybody else's.
   */
  private static List<Group> kindTail() {
    return List.of(
        Group.ofKind(DEFAULT_GROUP, PinKind.INTERNAL), Group.ofKind(EXTERNAL_GROUP, PinKind.EXTERNAL));
  }

  /**
   * Reads one file.
   *
   * <p>The document is loaded with a SafeConstructor: it is somebody else's repository, and a yaml
   * loader that instantiates arbitrary classes reads that repository as code.
   */
  public static Parsed parse(String yaml) {
    Object document;
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      document = new Yaml(new SafeConstructor(options)).load(yaml);
    } catch (RuntimeException e) {
      return invalid(PATH + " is not valid yaml: " + message(e));
    }
    if (document == null) {
      // An empty file is a file that says nothing, which is what an absent one says too.
      return fallback();
    }
    if (!(document instanceof Map<?, ?> root)) {
      return invalid(PATH + " must be a mapping with a `groups` key");
    }
    Object rawGroups = root.get("groups");
    if (rawGroups == null) {
      return fallback();
    }
    if (!(rawGroups instanceof List<?> list)) {
      return invalid("`groups` must be a list");
    }
    List<Group> groups = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Object element : list) {
      if (!(element instanceof Map<?, ?> entry)) {
        return invalid("every entry of `groups` must be a mapping with `name` and `deps`");
      }
      Object rawName = entry.get("name");
      if (!(rawName instanceof String name) || !NAME.matcher(name).matches()) {
        return invalid(
            "a group `name` must match " + NAME.pattern() + " — it becomes a branch name");
      }
      if (!seen.add(name)) {
        return invalid("the group `" + name + "` is declared twice");
      }
      Object rawDeps = entry.get("deps");
      if (!(rawDeps instanceof List<?> deps) || deps.isEmpty()) {
        return invalid("the group `" + name + "` must carry a non-empty `deps` list");
      }
      List<String> patterns = new ArrayList<>();
      for (Object dep : deps) {
        if (!(dep instanceof String pattern) || pattern.isBlank()) {
          return invalid("every entry of `" + name + "`'s `deps` must be a non-empty string");
        }
        patterns.add(pattern.trim());
      }
      groups.add(Group.glob(name, patterns));
    }
    if (groups.isEmpty()) {
      return fallback();
    }
    // EVERY PIN BELONGS SOMEWHERE, AND THE TAIL IS THE SPLIT. The configured groups claim what they
    // claim; whatever they do not claim falls to the same two kind groups an unconfigured
    // repository gets, appended last so neither ever takes a pin a configured group wanted.
    //
    // A repository that declares a group under one of the two built-in names keeps its own — the
    // name is then that repository's, globs and all, and the half it took is not appended a second
    // time. Both are checked: `dependencies` and `external` are ordinary names a file may use.
    for (Group tail : kindTail()) {
      if (!seen.contains(tail.name())) {
        groups.add(tail);
      }
    }
    return new Parsed(List.copyOf(groups), GroupSource.CONFIG, null);
  }

  private static Parsed invalid(String message) {
    return new Parsed(List.of(), GroupSource.CONFIG, message);
  }

  private static String message(RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.toString() : message.replace('\n', ' ');
  }
}
