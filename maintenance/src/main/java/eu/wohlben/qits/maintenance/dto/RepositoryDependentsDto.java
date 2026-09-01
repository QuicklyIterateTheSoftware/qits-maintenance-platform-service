package eu.wohlben.qits.maintenance.dto;

import java.util.List;

/**
 * <b>Who depends on what this repository publishes</b>, grouped by the artifact they depend on.
 *
 * <p>A repository can publish several artifacts — a jar, an npm package and an image out of one
 * reactor is the ordinary shape here — and "who is affected if I release this" is a question about
 * the repository rather than about any one of them. Grouping keeps the answer readable: the same
 * dependent usually appears under several of a repository's artifacts, and a flat list would say
 * so five times without saying which.
 *
 * @param repository the repository whose released artifacts these are
 * @param artifacts one entry per artifact it produced, each with everyone who embeds it
 */
public record RepositoryDependentsDto(String repository, List<ArtifactDependentsDto> artifacts) {

  /**
   * One of the repository's artifacts, with its dependents.
   *
   * @param ecosystem the artifact's world
   * @param name the artifact
   * @param latest the newest version this service knows for it ({@code mt_latest}), null when no
   *     lookup or event has answered yet — what lets a reader judge each dependent's embedded
   *     version as current or behind without a second request
   * @param dependents the newest release of every artifact of ours that embeds it
   */
  public record ArtifactDependentsDto(
      String ecosystem, String name, String latest, List<DependentDto> dependents) {}
}
