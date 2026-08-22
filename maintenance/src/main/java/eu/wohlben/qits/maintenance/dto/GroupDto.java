package eu.wohlben.qits.maintenance.dto;

/**
 * One group of one repository, as both listings serve it.
 *
 * @param name the group, which is also the branch suffix
 * @param source CONFIG or DEFAULT — whether the repository asked for this grouping
 * @param branch the ref name a bump writes
 * @param state the branch's state, or NONE when nothing has ever pushed it
 * @param headSha the branch head as last read, null when the branch does not exist
 * @param pending how many changes this group would carry right now
 */
public record GroupDto(
    String name, String source, String branch, String state, String headSha, int pending) {}
