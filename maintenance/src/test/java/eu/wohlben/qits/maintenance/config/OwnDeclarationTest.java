package eu.wohlben.qits.maintenance.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * This repository's own {@code .config/qits/configuration.yml}.
 *
 * <p>This is not the parser. qits-configuration's {@code DeclarationParser} is, and it was run
 * against the file when it was written. This test holds only what a hand edit can break without
 * anyone seeing it until a release is refused or a needed entry shows as orphaned.
 */
class OwnDeclarationTest {

  private static final String PATH = ".config/qits/configuration.yml";

  /** {@code ConfigurationKeys.ENV_KEY} and {@code INDEXED_KEY}, copied: the store's key grammar. */
  private static final Pattern KEY =
      Pattern.compile(
          "^env\\.[A-Za-z_][A-Za-z0-9_]*$|^(mounts|publishes|groups|aliases)\\[[0-9]{1,4}]$");

  /** The file is found by walking up from the module directory surefire starts in. */
  private static Path declaration() {
    Path at = Path.of("").toAbsolutePath();
    for (int up = 0; up < 4 && at != null; up++, at = at.getParent()) {
      Path candidate = at.resolve(PATH);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new AssertionError("no " + PATH + " above " + Path.of("").toAbsolutePath());
  }

  /** Loaded safely, with duplicate keys refused, as the store refuses them. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> keys() throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Object document =
        new Yaml(new SafeConstructor(options)).load(Files.readString(declaration()));
    Map<String, Object> root = assertInstanceOf(Map.class, document);
    assertEquals(Set.of("keys"), root.keySet(), "the top level holds `keys` and nothing else");
    return assertInstanceOf(Map.class, root.get("keys"));
  }

  @Test
  void everyKeyIsSpelledInTheStoresGrammar() throws IOException {
    Map<String, Object> keys = keys();

    assertFalse(keys.isEmpty());
    for (String key : keys.keySet()) {
      assertTrue(KEY.matcher(key).matches(), "not a store key: " + key);
    }
  }

  /**
   * NO DEFAULT, AND ONLY THE THREE LITERAL TYPES — for now. A default would sit under the stored
   * entries and never be seen; a serviceAddress would make the stored address an ignored row. This
   * file only lists keys. The change that adds a default or a rendered type changes this test too.
   */
  @Test
  void everyKeyIsATypedNameWithNoDefault() throws IOException {
    for (Map.Entry<String, Object> entry : keys().entrySet()) {
      Map<?, ?> shape = assertInstanceOf(Map.class, entry.getValue(), entry.getKey());
      assertTrue(
          Set.of("type", "description").containsAll(shape.keySet()),
          entry.getKey() + " carries " + shape.keySet());
      assertTrue(
          Set.of("string", "boolean", "number").contains(shape.get("type")),
          entry.getKey() + " is typed " + shape.get("type"));
    }
  }

  /**
   * THE `qits` CLIENT'S FALLBACK IS THE OLD `projects` CLIENT, and only three of its four read keys
   * are declared here. `_AUTH_SERVER_URL` is out: the shipped config DERIVES that address from
   * QITS_ENVIRONMENT, which every container is given, so a stored entry carrying it states nothing
   * this process could not work out. (It named qits-platform-idp by a bare alias until the platform
   * plane was deleted — one process for the whole estate, one address estate-wide. The address moved
   * and stopped being worth storing in the same change.) Its audience key and the githost and ci
   * clients' keys are read by nothing either, so all of them must stay out and show as orphaned. If
   * the fallback in microprofile-config.properties moves, this moves with it.
   */
  @Test
  void onlyTheQitsClientsFallbackKeysAreDeclared() throws IOException {
    Set<String> declared =
        keys().keySet().stream()
            .filter(key -> key.startsWith("env.QUARKUS_OIDC_CLIENT_"))
            .collect(Collectors.toSet());

    assertEquals(
        Set.of(
            "env.QUARKUS_OIDC_CLIENT_PROJECTS_CLIENT_ENABLED",
            "env.QUARKUS_OIDC_CLIENT_PROJECTS_CLIENT_ID",
            "env.QUARKUS_OIDC_CLIENT_PROJECTS_CREDENTIALS_SECRET"),
        declared);
  }
}
