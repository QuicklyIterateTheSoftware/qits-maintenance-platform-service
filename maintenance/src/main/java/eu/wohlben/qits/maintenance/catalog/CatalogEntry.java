package eu.wohlben.qits.maintenance.catalog;

/**
 * One repository as the catalog names it — the coordinate every other read this service makes is
 * built from.
 *
 * @param project the project id the git host serves it under; the first half of
 *     {@code /git/<project>/<repo>}
 * @param name the addressable repository name
 * @param mainBranch the branch a scan reads, as qits-projects records it
 */
public record CatalogEntry(String project, String name, String mainBranch) {}
