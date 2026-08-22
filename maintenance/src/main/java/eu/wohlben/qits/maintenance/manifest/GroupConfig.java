package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.maintenance.model.GroupSource;
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
 * <p><b>The file is {@code .config/qits/maintenance.yml} and it is optional.</b> A repository that
 * carries none gets one group, {@code dependencies}, claiming everything — which is the behaviour
 * the 71 per-dependency hop files replaced, only with one branch instead of one per upstream.
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

  /** What a repository with no file gets: one group, claiming every pin. */
  public static final String DEFAULT_GROUP = "dependencies";

  /** The one glob of the default group. */
  public static final String MATCH_ALL = "*";

  /** A group's name reaches a branch name and a shell, so it is held to what a ref may carry. */
  private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  private GroupConfig() {}

  /**
   * One group as configured.
   *
   * @param name the group, which is also the branch suffix
   * @param patterns the globs on a dependency name
   */
  public record Group(String name, List<String> patterns) {}

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

  /** What a repository with no {@code .config/qits/maintenance.yml} gets. */
  public static Parsed fallback() {
    return new Parsed(
        List.of(new Group(DEFAULT_GROUP, List.of(MATCH_ALL))), GroupSource.DEFAULT, null);
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
      groups.add(new Group(name, List.copyOf(patterns)));
    }
    if (groups.isEmpty()) {
      return fallback();
    }
    // EVERY PIN BELONGS SOMEWHERE. The configured groups claim what they claim, and whatever they
    // do not claim falls to the same default group an unconfigured repository gets — declared last
    // so it never takes a pin a configured group wanted.
    if (!seen.contains(DEFAULT_GROUP)) {
      groups.add(new Group(DEFAULT_GROUP, List.of(MATCH_ALL)));
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
