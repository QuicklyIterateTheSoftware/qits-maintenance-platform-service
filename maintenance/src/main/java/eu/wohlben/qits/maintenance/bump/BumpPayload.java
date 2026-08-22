package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.pending.Change;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What the bump step will accept, checked HERE before anything is sent.
 *
 * <p><b>The step validates the same things and refuses the same way.</b> Every value below reaches
 * a shell, a git ref or a file path in somebody else's repository, so the step holds them to a
 * character set — and a payload that fails over there is a red run, a red run row and a person
 * reading a step log to find out that a group was named with a slash. Checking first turns that
 * into a sentence on the bump row, written by the component that composed the payload.
 *
 * <p><b>What the step does NOT enforce, and what that means here.</b>
 *
 * <ul>
 *   <li>{@code from} is not a precondition. A manifest already at {@code to} is a quiet no-op, so a
 *       change computed against a pin that has since moved costs nothing — it simply does not
 *       write, and the branch not moving is what this service reads as NOTHING_TO_DO.
 *   <li>{@code location} is honoured for MAVEN ONLY — {@code property:<name>} or
 *       {@code dependency:<groupId>:<artifactId>}. npm edits whichever section holds the entry and
 *       docker anchors on the image name, so their locations are carried and ignored. They are sent
 *       anyway: the inventory records where a pin is, and a future step that wants it should not
 *       need a payload change.
 *   <li>For maven, {@code name} is COMMIT-MESSAGE ONLY. {@code location} carries the coordinates
 *       the step edits by, which is why a maven location has to be exact and a misspelled one is a
 *       silent no-op rather than a wrong edit.
 * </ul>
 */
public final class BumpPayload {

  /** A version reaches a shell and a docker reference. */
  static final Pattern VERSION = Pattern.compile("[0-9A-Za-z._+-]+");

  /** A group name becomes half a branch name. */
  static final Pattern GROUP = Pattern.compile("[0-9A-Za-z._-]+");

  /** A plain ref: segments of the same characters, slashes between them, nothing git rejects. */
  static final Pattern REF = Pattern.compile("[0-9A-Za-z._-]+(?:/[0-9A-Za-z._-]+)*");

  private BumpPayload() {}

  /**
   * Every reason this payload cannot be sent, or an empty list.
   *
   * <p>Every problem is reported rather than the first: a repository whose configuration produces
   * three bad entries should be told three times, not made to fix them one run at a time.
   */
  public static List<String> problems(String group, String branch, String baseRef, List<Change> changes) {
    List<String> problems = new ArrayList<>();
    if (group == null || !GROUP.matcher(group).matches()) {
      problems.add("the group name '" + group + "' is not " + GROUP.pattern());
    }
    if (branch == null || !REF.matcher(branch).matches()) {
      problems.add("the branch '" + branch + "' is not a plain ref");
    }
    if (baseRef == null || !REF.matcher(baseRef).matches()) {
      problems.add("the base ref '" + baseRef + "' is not a plain ref");
    }
    for (Change change : changes) {
      if (change.to() == null || !VERSION.matcher(change.to()).matches()) {
        problems.add("the target version of " + change.name() + " is not " + VERSION.pattern());
      }
      String path = change.manifestPath();
      if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")) {
        problems.add(
            "the manifest path of " + change.name() + " must be relative and free of '..': " + path);
      }
    }
    return List.copyOf(problems);
  }
}
