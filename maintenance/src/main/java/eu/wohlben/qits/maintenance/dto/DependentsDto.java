package eu.wohlben.qits.maintenance.dto;

import java.util.List;

/**
 * <b>Who ships a copy of this dependency</b> — the SBOM's answer to the question {@code
 * GET /dependencies} answers from manifests.
 *
 * <p>The two are deliberately separate routes because they are separate facts. A pin is what a
 * repository would EDIT; a dependent is what a released package CONTAINS, transitives included. A
 * release that fixed a vulnerable transitive is invisible to the first and is the whole subject of
 * the second.
 *
 * @param ecosystem the dependency's world
 * @param name the dependency
 * @param latest its newest published version, from {@code mt_latest}; null is ordinary — the
 *     dependency may be one nothing on this platform pins, so no lookup has ever run for it
 * @param dependents every artifact of ours that embeds it, by default the newest released version
 *     of each
 */
public record DependentsDto(
    String ecosystem, String name, String latest, List<DependentDto> dependents) {}
