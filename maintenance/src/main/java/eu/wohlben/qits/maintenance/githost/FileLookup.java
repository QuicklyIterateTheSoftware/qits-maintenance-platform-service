package eu.wohlben.qits.maintenance.githost;

/**
 * What one file read at one revision produced.
 *
 * <p><b>The five-outcome model is qits-ci's {@code HttpGitConfigSource}</b>, and copying it is the
 * point: the git host answers a missing revision and a missing path with the same bare 404, so a
 * single "not found" would make "this repository has no package.json" indistinguishable from "this
 * commit does not exist here". Telling them apart takes a second call, and the vocabulary is what
 * records which one was made.
 *
 * @param status what happened
 * @param content the file as text, non-null only for {@link Status#FOUND}
 * @param message the sentence, non-null for {@link Status#UNREACHABLE} and {@link Status#INVALID}
 */
public record FileLookup(Status status, String content, String message) {

  public enum Status {
    /** The file is there, and {@code content} is it. */
    FOUND,

    /** The commit exists and does not carry this file. An ordinary answer, not a fault. */
    ABSENT,

    /** The repository does not hold this commit at all. */
    GONE,

    /** The git host could not be asked, or refused. Nothing is concluded about the file. */
    UNREACHABLE,

    /** The file is there and cannot be used — too large to read. */
    INVALID
  }

  public static FileLookup found(String content) {
    return new FileLookup(Status.FOUND, content, null);
  }

  public static FileLookup absent() {
    return new FileLookup(Status.ABSENT, null, null);
  }

  public static FileLookup gone() {
    return new FileLookup(Status.GONE, null, null);
  }

  public static FileLookup unreachable(String message) {
    return new FileLookup(Status.UNREACHABLE, null, message);
  }

  public static FileLookup invalid(String message) {
    return new FileLookup(Status.INVALID, null, message);
  }

  public boolean found() {
    return status == Status.FOUND;
  }
}
