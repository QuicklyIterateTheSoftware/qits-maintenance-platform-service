package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;

/**
 * One released artifact of ours that SHIPS a copy of some dependency.
 *
 * <p><b>This is a different claim from a pin's, and the wording keeps them apart.</b> A pin is a
 * line in a manifest that a bump can edit; this is a component inside a package that was built and
 * published, which no line anywhere may name. An artifact can embed a dependency it does not pin —
 * that is exactly what {@code direct: false} means — and a repository can pin something none of its
 * artifacts ship.
 *
 * @param artifactEcosystem which world the DEPENDENT is published in
 * @param artifactName the dependent artifact, in {@code mt_pin}'s spelling
 * @param artifactVersion the released version whose document said so
 * @param repository the repository that produced it, as the release announced it
 * @param embeddedVersion the version of the dependency that this artifact ships
 * @param direct whether the artifact declares it, rather than pulling it in behind something else
 * @param occurredAt when that version was released
 * @param sbomStatus the artifact's ingest state, so a stale reading is visible as one
 */
public record DependentDto(
    String artifactEcosystem,
    String artifactName,
    String artifactVersion,
    String repository,
    String embeddedVersion,
    boolean direct,
    Instant occurredAt,
    String sbomStatus) {}
