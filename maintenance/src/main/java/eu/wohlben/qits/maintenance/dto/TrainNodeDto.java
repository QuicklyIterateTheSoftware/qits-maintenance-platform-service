package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One expected adopter of one release, and how far it has got.
 *
 * <p><b>{@code consumerCatalogId} is what makes a node CLICKABLE, and it is the whole reason this
 * record joins {@code mt_repository} at all.</b> qits-projects addresses a repository by its own row
 * id, so the release request an adoption opened lives at {@code
 * release-requests/by-release/<consumerCatalogId>/<adoptedVersion>} — neither half of which is
 * derivable from the consumer's NAME, which is all a node row stores.
 *
 * <p><b>What is copied from the node and what is read live are deliberately different.</b> {@code
 * archetype} is the node's own column, frozen at spawn, because the train is a log and a repository
 * re-classified next month did not retroactively change the kind of adoption this node was placed
 * for. {@code consumerCatalogId} and {@code consumerStatus} are read from the inventory as it is
 * NOW, because they are not history: they are the address a reader would follow and the reason
 * following it might be pointless.
 *
 * @param id the node
 * @param consumer who is expected to adopt: a repository NAME for LINKED and DAEMON_PIN, and the
 *     APPLICATION name for CONFIG_IMAGE_PIN — two namespaces that mostly agree
 * @param consumerCatalogId that repository's id in qits-projects, or null. <b>Null is ordinary</b>:
 *     a CONFIG_IMAGE_PIN node names an application rather than a repository, so nothing resolves it
 *     and there is no release request to link to — an application does not release
 * @param consumerStatus what the inventory currently says about the consumer — OK, ABSENT,
 *     UNREACHABLE or CONFIG_ERROR — or null when no row resolves the name at all. <b>ABSENT is
 *     worth showing beside a PENDING node</b>: the train is waiting on a repository the catalog no
 *     longer lists, and that node is never going to move on its own
 * @param archetype what the consumer WAS when the node was placed, verbatim and unvalidated; null
 *     for a consumer the catalog had not classified
 * @param endKind LINKED, CONFIG_IMAGE_PIN or DAEMON_PIN — which end the adoption is owed through,
 *     and the field a reader must branch on before treating {@code adoptedVersion} as an address
 * @param state PENDING, ADOPTED or LANDED
 * @param adoptedVersion <b>THE CONSUMER'S OWN RELEASE that carries the adoption</b> for a LINKED
 *     node — not the version of the dependency it took — and for the two POLLED ends the version
 *     that was OBSERVED instead, because an application does not release and a service handing out
 *     a daemon build published nothing by doing so. Null while PENDING
 * @param adoptedAt when the adoption was first seen
 * @param childTrainId the adopting release's own train, when adopting produced one. <b>This is the
 *     link the journey view stitches on</b> — the backend serves one train at a time and the client
 *     follows these — and null both while nothing has been adopted and in the window before the
 *     sibling release's station is opened
 * @param landedAt when the adoption was released and integrated; null until it was
 */
public record TrainNodeDto(
    UUID id,
    String consumer,
    String consumerCatalogId,
    String consumerStatus,
    String archetype,
    String endKind,
    String state,
    String adoptedVersion,
    Instant adoptedAt,
    UUID childTrainId,
    Instant landedAt) {}
