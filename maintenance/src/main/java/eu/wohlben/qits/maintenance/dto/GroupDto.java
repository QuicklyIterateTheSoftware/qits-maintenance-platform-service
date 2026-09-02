package eu.wohlben.qits.maintenance.dto;

/**
 * One group of one repository, as both listings serve it.
 *
 * <p><b>{@code kind} and {@code source} are two different questions.</b> {@code source} says
 * whether the repository asked for this grouping; {@code kind} says HOW the group claims — by the
 * pins' kind, or by globs the repository wrote. The fallback pair is DEFAULT and kinded; a
 * configured group is CONFIG and carries no kind.
 *
 * @param name the group, which is also the branch suffix
 * @param source CONFIG or DEFAULT — whether the repository asked for this grouping
 * @param kind INTERNAL or EXTERNAL when the group claims by kind, null when its globs decide
 * @param branch the ref name a bump writes
 * @param state the branch's state, or NONE when nothing has ever pushed it
 * @param headSha the branch head as last read, null when the branch does not exist
 * @param pending how many changes this group would carry right now
 */
public record GroupDto(
    String name,
    String source,
    String kind,
    String branch,
    String state,
    String headSha,
    int pending) {}
