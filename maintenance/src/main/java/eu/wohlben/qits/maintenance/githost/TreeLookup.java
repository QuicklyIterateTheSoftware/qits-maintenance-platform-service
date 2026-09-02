package eu.wohlben.qits.maintenance.githost;

import java.util.List;

/**
 * One directory listing at one revision, and the commit it resolved to.
 *
 * <p><b>{@code headSha} is why a tree of the root is the first call of every scan.</b> The git host
 * stamps every tree and blob answer with {@code Git-Commit-Sha}, so asking for the root tree at
 * {@code main} resolves the branch to a commit AND proves the repository is readable in one call.
 * Every manifest is then read at that sha rather than at the branch, which is what makes a scan a
 * snapshot of one tree instead of a mixture of whatever moved during it.
 *
 * @param status what happened; the same vocabulary a file read uses
 * @param headSha the commit the revision resolved to, non-null for {@link FileLookup.Status#FOUND}
 * @param entries the directory's entries, empty unless FOUND
 * @param message the sentence, for UNREACHABLE
 */
public record TreeLookup(
    FileLookup.Status status, String headSha, List<TreeEntry> entries, String message) {

  /**
   * One entry of a tree, and the two fields the git host reports beyond its name.
   *
   * <p><b>{@code type} is a two-valued collapse and always has been</b>: the host answers
   * {@code tree} for a directory and {@code blob} for everything else, so a symlink and a submodule
   * gitlink both arrive as {@code blob}. What tells a gitlink apart is the {@code mode} — git's
   * {@code 160000} — or a host that answers the git object type {@code commit} in {@code type}.
   * Both are read, because only one of them exists on the deployed git host at a time.
   *
   * <p><b>{@code sha} is the ENTRY's object name, not the commit's</b>, and for a gitlink it is the
   * whole of what a submodule pin is. It is null on a host that does not report it, which is what
   * makes a gitlink unpinnable there rather than pinnable at a wrong version — see
   * {@code ManifestScanner}.
   *
   * @param name the entry's own name, not a path
   * @param type {@code tree}, {@code blob}, or {@code commit} on a host that spells a gitlink out
   * @param mode the octal file mode as text ({@code 160000} for a gitlink), null when not reported
   * @param sha the entry's object name, null when not reported
   */
  public record TreeEntry(String name, String type, String mode, String sha) {

    /** Git's mode for a gitlink — a commit recorded inside a tree. */
    public static final String GITLINK_MODE = "160000";

    /** The two-field entry a git host without modes or shas answers. */
    public TreeEntry(String name, String type) {
      this(name, type, null, null);
    }

    public boolean isBlob() {
      return "blob".equals(type) && !isGitlink();
    }

    public boolean isTree() {
      return "tree".equals(type);
    }

    /** Whether this entry is a submodule: the mode says so, or the type names a commit. */
    public boolean isGitlink() {
      return GITLINK_MODE.equals(mode) || "commit".equals(type);
    }
  }

  public static TreeLookup found(String headSha, List<TreeEntry> entries) {
    return new TreeLookup(FileLookup.Status.FOUND, headSha, List.copyOf(entries), null);
  }

  public static TreeLookup absent() {
    return new TreeLookup(FileLookup.Status.ABSENT, null, List.of(), null);
  }

  public static TreeLookup gone() {
    return new TreeLookup(FileLookup.Status.GONE, null, List.of(), null);
  }

  public static TreeLookup unreachable(String message) {
    return new TreeLookup(FileLookup.Status.UNREACHABLE, null, List.of(), message);
  }

  public boolean found() {
    return status == FileLookup.Status.FOUND;
  }

  /** Whether the listing holds a blob of that exact name. */
  public boolean hasBlob(String name) {
    return entries.stream().anyMatch(entry -> entry.isBlob() && entry.name().equals(name));
  }

  /** The entry of that exact name, whatever its kind. */
  public java.util.Optional<TreeEntry> entry(String name) {
    return entries.stream().filter(entry -> entry.name().equals(name)).findFirst();
  }
}
