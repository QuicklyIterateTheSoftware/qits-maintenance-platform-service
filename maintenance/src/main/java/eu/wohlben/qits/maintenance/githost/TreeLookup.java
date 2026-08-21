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
   * One entry of a tree. The git host reports two fields and no more — there is no per-entry sha
   * and no path, because the listing is not recursive.
   *
   * @param name the entry's own name, not a path
   * @param type {@code tree} or {@code blob}; a gitlink and a symlink both report {@code blob}
   */
  public record TreeEntry(String name, String type) {

    public boolean isBlob() {
      return "blob".equals(type);
    }

    public boolean isTree() {
      return "tree".equals(type);
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
}
