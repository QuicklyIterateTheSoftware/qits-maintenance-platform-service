package eu.wohlben.qits.maintenance.dto;

/**
 * One thing this repository's released artifacts CONTAIN that no manifest of theirs names.
 *
 * <p><b>This is the half of the dependency picture a pin cannot show, and it is deliberately not a
 * pin.</b> There is no line to edit, no location, no group and no bump — which is exactly why it is
 * a section of its own rather than more rows in {@code pins}. What it is for is seeing: a
 * vulnerable library three levels down is invisible on a repository page built from manifests
 * alone, and it is the first thing anybody asks about after an advisory.
 *
 * <p><b>Pins are removed from this list.</b> A component the repository also declares is already on
 * the page, with a version, a latest and a verdict; repeating it here as a transitive would say the
 * opposite of what is true about it.
 *
 * @param ecosystem the component's world, or null when the purl named one this service does not
 *     inventory — such a component is shown and never matched against anything
 * @param name the component
 * @param version the version the artifact ships
 * @param via the DIRECT component whose subtree pulled it in, or null when the artifact's own root
 *     names it. Where several directs reach it, the first by name — a graph has many paths and a
 *     page needs one
 * @param behind whether {@code mt_latest} knows a newer version in that ecosystem's order; false
 *     whenever nothing is known, because "we could not find out" must not read like "you are behind"
 */
public record TransitiveDto(
    String ecosystem, String name, String version, String via, boolean behind) {}
