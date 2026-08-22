package eu.wohlben.qits.maintenance.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads the direct pins of one {@code package.json}, resolved through its lock.
 *
 * <p><b>The manifest says what is ALLOWED and the lock says what is INSTALLED.</b> A range like
 * {@code ^21.0.0} is not a version and cannot be compared with a registry's latest — three
 * different installs of it are three different trees. So the version on a pin is the lock's
 * resolved one, and the range travels beside it as {@code range}, which is what a reader needs to
 * judge whether a bump is inside what the author allowed.
 *
 * <p><b>A dependency the lock does not resolve is not a pin.</b> It means the lock is out of step
 * with the manifest, and inventing a version from the range would be this service guessing at
 * somebody else's tree.
 *
 * <p><b>The nested {@code webui/} package.json is not read here and never reaches this parser</b> —
 * it is a gitlink to its own repository, which the catalog lists separately. See {@link
 * ManifestScanner}, which is where the discovery stops.
 */
public final class NpmParser {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The two blocks that hold direct dependencies, and the location a pin records. */
  private static final List<String> BLOCKS = List.of("dependencies", "devDependencies");

  private NpmParser() {}

  /**
   * The pins of one package.json.
   *
   * @param manifestPath the manifest's repository-relative path
   * @param packageJson the manifest
   * @param packageLockJson its lock, or null when the repository carries none
   */
  public static List<ParsedPin> parse(String manifestPath, String packageJson, String packageLockJson) {
    JsonNode manifest = read(packageJson);
    if (manifest == null) {
      return List.of();
    }
    Map<String, String> locked = lockedVersions(packageLockJson);
    List<ParsedPin> pins = new ArrayList<>();
    for (String block : BLOCKS) {
      JsonNode dependencies = manifest.get(block);
      if (dependencies == null || !dependencies.isObject()) {
        continue;
      }
      Iterator<Map.Entry<String, JsonNode>> fields = dependencies.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String name = entry.getKey();
        JsonNode range = entry.getValue();
        if (!range.isTextual()) {
          continue;
        }
        String version = locked.get(name);
        if (version == null || version.isBlank()) {
          continue;
        }
        pins.add(
            ParsedPin.of(Ecosystem.NPM, manifestPath, name, version, range.asText(), block));
      }
    }
    return List.copyOf(pins);
  }

  /**
   * The lock's resolved version per package name.
   *
   * <p><b>Two lock shapes.</b> Lockfile v2 and v3 key {@code packages} by an install PATH, so a
   * top-level dependency is {@code node_modules/<name>} — a nested one is
   * {@code node_modules/a/node_modules/b} and is deliberately not read: it is transitive. Lockfile
   * v1 keys {@code dependencies} by name directly, and is read as the fallback.
   */
  static Map<String, String> lockedVersions(String packageLockJson) {
    JsonNode lock = read(packageLockJson);
    if (lock == null) {
      return Map.of();
    }
    Map<String, String> versions = new java.util.LinkedHashMap<>();
    JsonNode packages = lock.get("packages");
    if (packages != null && packages.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = packages.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        String path = entry.getKey();
        if (!path.startsWith("node_modules/")) {
          continue;
        }
        String name = path.substring("node_modules/".length());
        if (name.contains("/node_modules/")) {
          continue;
        }
        JsonNode version = entry.getValue().get("version");
        if (version != null && version.isTextual()) {
          versions.put(name, version.asText());
        }
      }
    }
    JsonNode legacy = lock.get("dependencies");
    if (versions.isEmpty() && legacy != null && legacy.isObject()) {
      Iterator<Map.Entry<String, JsonNode>> fields = legacy.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> entry = fields.next();
        JsonNode version = entry.getValue().get("version");
        if (version != null && version.isTextual()) {
          versions.put(entry.getKey(), version.asText());
        }
      }
    }
    return versions;
  }

  private static JsonNode read(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(json);
      return node != null && node.isObject() ? node : null;
    } catch (Exception e) {
      return null;
    }
  }
}
