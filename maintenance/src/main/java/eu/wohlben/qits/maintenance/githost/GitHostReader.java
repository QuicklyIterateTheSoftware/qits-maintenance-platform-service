package eu.wohlben.qits.maintenance.githost;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Files and directories from qits-githost, read at one revision.
 *
 * <p><b>The model is qits-ci's {@code HttpGitConfigSource}</b> and so is the reason for it: the git
 * host answers "no such revision" and "no such path" with the same bare 404, so a 404 on a file is
 * followed by a listing of the repository root at that same revision. A root that answers means the
 * commit is there and the file is not — ABSENT. A root that 404s means the commit itself is not
 * there — GONE.
 *
 * <p><b>The routes are name-addressed</b>, {@code /git/<project>/<repo>/…}, which is the scheme the
 * repository-identity ruling makes the only clone URL. The id-addressed scheme beside it is
 * qits-projects' own and is refused to anyone else.
 *
 * <p><b>A revision is one path segment</b>, so a slash in a branch name is percent-encoded. It
 * matters for {@code maintenance/dependencies}, which is exactly the shape of every branch this
 * service reads.
 */
@ApplicationScoped
public class GitHostReader {

  /** The header every tree and blob answer carries: the commit the revision resolved to. It is
   * deliberately not an {@code X-Qits-*} name — the gateway strips that prefix. */
  public static final String COMMIT_SHA_HEADER = "Git-Commit-Sha";

  /** The git host refuses a blob past 8 MiB; nothing this service reads is near it, and a manifest
   * that is would be a repository problem rather than a limit to raise. */
  public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

  @Inject PeerClient peers;

  /**
   * The commit a revision resolves to, proved by reading the repository's root tree.
   *
   * <p>This is the FIRST call of every repository's scan and the only one that resolves a branch.
   * Every manifest read afterwards names the sha, so the whole inventory of one repository is a
   * snapshot of one tree.
   */
  public TreeLookup head(String project, String repository, String revision) {
    return tree(project, repository, revision, "");
  }

  /** One directory listing. An empty path is the repository root. */
  public TreeLookup tree(String project, String repository, String revision, String path) {
    PeerAnswer answer = peers.get(PeerTarget.GITHOST, treePath(project, repository, revision, path)).answer();
    if (answer.ok()) {
      Optional<String> sha = answer.header(COMMIT_SHA_HEADER);
      if (sha.isEmpty() || sha.get().isBlank()) {
        // A 200 with no sha is not an answer about a revision: nothing read at it could be pinned
        // to a commit, and a scan that recorded pins with no sha would be an inventory of nothing
        // in particular.
        return TreeLookup.unreachable(
            "the git host answered a tree of " + repository + " with no " + COMMIT_SHA_HEADER);
      }
      return TreeLookup.found(sha.get(), entries(answer.json()));
    }
    if (!answer.notFound()) {
      return TreeLookup.unreachable(
          "the git host could not list " + repository + "/" + path + ": " + answer.failure());
    }
    // 404 on the ROOT of a repository is the revision, not the path — there is no path left to be
    // missing. Deeper, the second call tells them apart.
    if (path.isEmpty()) {
      return TreeLookup.gone();
    }
    return switch (rootStatus(project, repository, revision)) {
      case FOUND -> TreeLookup.absent();
      case GONE -> TreeLookup.gone();
      default -> TreeLookup.unreachable(
          "the git host could not be asked whether " + repository + " holds " + revision);
    };
  }

  /** One file, as text. */
  public FileLookup blob(String project, String repository, String revision, String path) {
    PeerAnswer answer = peers.get(PeerTarget.GITHOST, blobPath(project, repository, revision, path)).answer();
    if (answer.ok()) {
      String content = answer.body();
      if (content == null) {
        return FileLookup.invalid(path + " is larger than this service reads");
      }
      return FileLookup.found(content);
    }
    if (answer.httpStatus() != null && answer.httpStatus() == 413) {
      return FileLookup.invalid(path + " is larger than the git host serves");
    }
    if (!answer.notFound()) {
      return FileLookup.unreachable(
          "the git host could not serve " + repository + "/" + path + ": " + answer.failure());
    }
    return switch (rootStatus(project, repository, revision)) {
      case FOUND -> FileLookup.absent();
      case GONE -> FileLookup.gone();
      default -> FileLookup.unreachable(
          "the git host could not be asked whether " + repository + " holds " + revision);
    };
  }

  /**
   * The second call: does this repository hold this revision at all?
   *
   * <p>It reads the ROOT tree, which every commit has, so the only thing it can be missing is the
   * commit.
   */
  private FileLookup.Status rootStatus(String project, String repository, String revision) {
    PeerAnswer answer = peers.get(PeerTarget.GITHOST, treePath(project, repository, revision, "")).answer();
    if (answer.ok()) {
      return FileLookup.Status.FOUND;
    }
    if (answer.notFound()) {
      return FileLookup.Status.GONE;
    }
    return FileLookup.Status.UNREACHABLE;
  }

  static String treePath(String project, String repository, String revision, String path) {
    String base = "/git/" + project + "/" + repository + "/tree/" + encodeRevision(revision);
    return path == null || path.isEmpty() ? base : base + "/" + path;
  }

  static String blobPath(String project, String repository, String revision, String path) {
    return "/git/" + project + "/" + repository + "/blob/" + encodeRevision(revision) + "/" + path;
  }

  /** A revision is ONE path segment on this route, so a slashy branch has to be encoded. Every
   * branch this service reads is {@code maintenance/<group>}. */
  static String encodeRevision(String revision) {
    return revision.replace("/", "%2F");
  }

  private static List<TreeLookup.TreeEntry> entries(JsonNode body) {
    List<TreeLookup.TreeEntry> entries = new ArrayList<>();
    if (body == null || !body.hasNonNull("entries") || !body.get("entries").isArray()) {
      return entries;
    }
    for (JsonNode entry : body.get("entries")) {
      JsonNode name = entry.get("name");
      JsonNode type = entry.get("type");
      if (name != null && name.isTextual() && type != null && type.isTextual()) {
        entries.add(new TreeLookup.TreeEntry(name.asText(), type.asText()));
      }
    }
    return entries;
  }
}
