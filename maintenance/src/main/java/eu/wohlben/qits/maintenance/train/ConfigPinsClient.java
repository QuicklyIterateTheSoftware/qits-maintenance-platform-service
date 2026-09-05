package eu.wohlben.qits.maintenance.train;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>WHO RUNS WHICH IMAGE VERSION</b> — qits-configuration's image pins, read as the end of an IMAGE
 * repository's release train.
 *
 * <p>Every other adoption this service decides is decided against something it already holds: a pin
 * a scan read out of a manifest, or a component an SBOM ingest stored. <b>An image pin is neither.</b>
 * It is a line in a deployment configuration — {@code env.QITS_WORKSPACE_IMAGE_VERSION} on the
 * qits-workspaces application — that no repository in the catalog carries and no event announces.
 * The only way to know whether a released image is the one actually being deployed is to ask
 * qits-configuration, which is what this does.
 *
 * <p><b>{@code image} is UNQUALIFIED</b> — {@code qits/workspace}, no registry host — which is
 * exactly how {@code mt_artifact.name} spells a docker release and how {@code SoftwareRelease}
 * announces one. That is what lets the sweep join a pin row onto a train without parsing a
 * reference; a qualified name on either side would have to be normalised, and normalising a registry
 * host is guesswork.
 *
 * <p><b>Two namespaces meet on this answer.</b> {@code application} is a deployed application's id
 * over there ({@code qits-workspaces}), not a repository name — mostly the same word, occasionally
 * not — and it is what a {@code CONFIG_IMAGE_PIN} node's {@code consumer} column holds. {@code key}
 * is the configuration entry the version was read out of, so one application can pin one image
 * through more than one key; see {@code TrainSweep} for what that does to a landing.
 *
 * <p><b>Parsed as a tree, never bound to a record</b>, for the reason every peer read here is:
 * a Jackson-bound type would need a {@code @RegisterForReflection} entry to survive the native image
 * and would 500 in production while the JVM suite stayed green. A {@code JsonNode} asks nothing of
 * the build.
 *
 * <p><b>Nothing throws and an error is a value</b>, {@code catalog/CatalogReader}'s convention: an
 * unreachable qits-configuration must leave the nodes PENDING and cost one WARN, not end a sweep.
 */
@ApplicationScoped
public class ConfigPinsClient {

  /** qits-configuration's own API, whole path in the code — the configured target is a bare host. */
  public static final String PATH = "/configuration/api/pins";

  @Inject PeerClient peers;

  /**
   * One deployment configuration's image pin.
   *
   * @param image the unqualified image name, as a docker release announces it
   * @param version the version that configuration deploys
   * @param application the APPLICATION that runs it — a {@code CONFIG_IMAGE_PIN} node's consumer
   * @param key the configuration entry the version was read out of, kept so a WARN can name it
   */
  public record Pin(String image, String version, String application, String key) {}

  /**
   * The pins, or why there are none.
   *
   * <p><b>An empty list and a failure are different results</b> and the sweep must not confuse them:
   * an answer with no rows means nothing deploys the released image, which is a legitimate reading
   * that places no nodes; a read that failed means nothing is known, and a node that stays PENDING
   * is the only honest outcome.
   *
   * @param generatedAt the moment qits-configuration composed the answer, or null. It is what a
   *     landing is stamped with — a real observation rather than this service's clock
   * @param error the sentence, or null when the read succeeded
   */
  public record Result(Instant generatedAt, List<Pin> pins, String error) {

    public boolean ok() {
      return error == null;
    }
  }

  /** Every image pin qits-configuration holds, in one call. It takes no filter. */
  public Result read() {
    PeerAnswer answer = peers.get(PeerTarget.CONFIGURATION, PATH).answer();
    if (!answer.ok()) {
      return new Result(null, List.of(), "the image pins could not be read: " + answer.failure());
    }
    return parse(answer.json());
  }

  /**
   * The answer as rows.
   *
   * <p>Package-private and taking the tree rather than the client, so the shapes that matter — a
   * body that is not JSON at all, a row missing the two fields a join needs — are a test of one
   * method rather than a test of the transport.
   */
  static Result parse(JsonNode root) {
    if (root == null || !root.hasNonNull("pins") || !root.get("pins").isArray()) {
      return new Result(null, List.of(), "the image pins answered a body with no pins array");
    }
    List<Pin> pins = new ArrayList<>();
    for (JsonNode row : root.get("pins")) {
      String image = text(row, "image");
      String version = text(row, "version");
      String application = text(row, "application");
      if (image == null || version == null || application == null) {
        // A row missing any of the three cannot be joined onto a train or onto a node. It is
        // dropped rather than carried as a null: the answer is somebody else's and a partial row is
        // never evidence that an application took a version.
        continue;
      }
      pins.add(new Pin(image, version, application, text(row, "key")));
    }
    return new Result(instant(root, "generatedAt"), List.copyOf(pins), null);
  }

  private static String text(JsonNode row, String field) {
    JsonNode value = row == null ? null : row.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      return null;
    }
    return value.asText().trim();
  }

  /** An ISO instant, or null — an unparseable moment costs the landing its stamp and nothing else. */
  private static Instant instant(JsonNode row, String field) {
    String value = text(row, field);
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (RuntimeException unreadable) {
      return null;
    }
  }
}
