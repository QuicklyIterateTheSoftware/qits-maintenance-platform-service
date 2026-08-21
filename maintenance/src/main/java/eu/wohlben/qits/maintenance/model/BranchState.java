package eu.wohlben.qits.maintenance.model;

/**
 * What became of one group's maintenance branch.
 *
 * <p>The branch is the whole "merge request": there is no workspace and no review object, so this
 * column is the only place its life is recorded.
 */
public enum BranchState {
  /** Never pushed, or deleted by the release door's cleanup after it was released. The next bump
   * starts fresh from {@code main}. */
  NONE,

  /** It exists on the git host and this service put it there. A further bump commits on top of it,
   * ff-only. */
  PUSHED,

  /** SOMEBODY REWROTE IT BY HAND. The bump's push is ff-only and never forced, so a non-ff
   * rejection is a person's edit — they own the branch now, and this service stops writing to it
   * until it is gone. */
  STALE,

  /** It went through the workspaces release door. */
  RELEASED,

  /** The last bump run went red. */
  FAILED
}
