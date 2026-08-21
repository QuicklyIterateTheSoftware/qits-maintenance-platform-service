package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reads the direct pins of one {@code pom.xml}.
 *
 * <p><b>Three places a version is written and all three are read</b>: {@code dependencies},
 * {@code dependencyManagement} and {@code parent}. A repository that pins a version once in
 * dependencyManagement and uses it in three modules has ONE line to edit, and that line is what
 * this records.
 *
 * <p><b>A property reference is resolved and REMEMBERED, not merely expanded.</b> The pin's
 * location becomes {@code property:<name>} rather than the dependency element, because that is the
 * line a bump has to change — rewriting the dependency's own version element would replace an
 * expression with a literal and quietly take the artifact off the shared property.
 *
 * <p><b>Properties are looked up in this pom first, then in the ROOT pom.</b> That is the reactor's
 * own rule for a module whose parent is the root, which is the shape every repository here has. A
 * property defined in neither leaves the pin dropped: an unexpanded expression is not a version,
 * and comparing one with a registry's answer would produce nonsense rather than an error.
 *
 * <p><b>A dependency with no version of its own is NOT a pin.</b> It takes one from a BOM, so there
 * is no line here to edit; recording it would put an entry in the inventory that nothing could ever
 * bump.
 */
public final class PomParser {

  private PomParser() {}

  /**
   * The pins of one pom.
   *
   * @param manifestPath the pom's repository-relative path, recorded on every pin
   * @param xml the file
   * @param rootProperties the root pom's properties, for a module that inherits one; for the root
   *     itself pass an empty map — its own properties are read here anyway
   * @param rootManifestPath where those inherited properties are SET. A pin whose version comes
   *     from a root property is recorded against the ROOT pom, not against the module that uses it:
   *     the line a bump edits is the property, and the property is up there. Recording the module
   *     would send the step to a file that does not contain the value.
   */
  public static List<ParsedPin> parse(
      String manifestPath,
      String xml,
      Map<String, String> rootProperties,
      String rootManifestPath) {
    Element project = Xml.root(xml);
    if (project == null) {
      return List.of();
    }
    Map<String, String> own = properties(project);
    own.putIfAbsent("project.version", projectVersion(project));
    own.putIfAbsent("project.groupId", projectGroupId(project));
    Map<String, String> properties = new LinkedHashMap<>(rootProperties);
    properties.putAll(own);

    List<ParsedPin> pins = new ArrayList<>();
    Element parent = Xml.child(project, "parent");
    if (parent != null) {
      pin(manifestPath, rootManifestPath, own, parent, properties, "parent").ifPresent(pins::add);
    }
    for (Element dependency : dependencies(project)) {
      pin(manifestPath, rootManifestPath, own, dependency, properties, "dependency")
          .ifPresent(pins::add);
    }
    Element management = Xml.child(project, "dependencyManagement");
    if (management != null) {
      for (Element dependency : dependencies(management)) {
        pin(manifestPath, rootManifestPath, own, dependency, properties, "dependency")
            .ifPresent(pins::add);
      }
    }
    return dedupe(pins);
  }

  /** The properties block of one pom, as written. */
  public static Map<String, String> properties(String xml) {
    Element project = Xml.root(xml);
    return project == null ? Map.of() : properties(project);
  }

  static Map<String, String> properties(Element project) {
    Map<String, String> properties = new LinkedHashMap<>();
    Element block = Xml.child(project, "properties");
    if (block == null) {
      return properties;
    }
    NodeList children = block.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE) {
        properties.put(node.getNodeName(), Xml.text(node).trim());
      }
    }
    return properties;
  }

  /** The module directories a reactor pom names. */
  public static List<String> modules(String xml) {
    Element project = Xml.root(xml);
    if (project == null) {
      return List.of();
    }
    Element block = Xml.child(project, "modules");
    if (block == null) {
      return List.of();
    }
    List<String> modules = new ArrayList<>();
    for (Element module : Xml.children(block, "module")) {
      String value = Xml.text(module).trim();
      if (!value.isEmpty()) {
        modules.add(value);
      }
    }
    return modules;
  }

  /** Whether the text is a pom at all — the guard a CONFIG-less repository's stray file needs. */
  public static boolean parses(String xml) {
    return Xml.root(xml) != null;
  }

  private static Optional<ParsedPin> pin(
      String manifestPath,
      String rootManifestPath,
      Map<String, String> ownProperties,
      Element element,
      Map<String, String> properties,
      String kind) {
    String groupId = trimmed(Xml.childText(element, "groupId"));
    String artifactId = trimmed(Xml.childText(element, "artifactId"));
    String rawVersion = trimmed(Xml.childText(element, "version"));
    if (groupId == null || artifactId == null || rawVersion == null) {
      return Optional.empty();
    }
    String property = propertyName(rawVersion);
    String version = property == null ? rawVersion : properties.get(property);
    if (version == null || version.isBlank() || propertyName(version) != null) {
      return Optional.empty();
    }
    String location =
        property != null ? "property:" + property : kind + ":" + groupId + ":" + artifactId;
    // Where the LINE is, which is not always where the dependency is: a property this pom does not
    // declare came from the root, and that is the file the step has to edit.
    String editedFile =
        property != null && !ownProperties.containsKey(property) ? rootManifestPath : manifestPath;
    return Optional.of(
        new ParsedPin(
            Ecosystem.MAVEN,
            editedFile,
            groupId + ":" + artifactId,
            version.trim(),
            null,
            location));
  }

  /** A property reference to its name, or null when the value is a literal. */
  static String propertyName(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.startsWith("${") && trimmed.endsWith("}") && trimmed.length() > 3) {
      return trimmed.substring(2, trimmed.length() - 1);
    }
    return null;
  }

  private static List<Element> dependencies(Element parent) {
    Element block = Xml.child(parent, "dependencies");
    return block == null ? List.of() : Xml.children(block, "dependency");
  }

  private static String projectVersion(Element project) {
    String own = trimmed(Xml.childText(project, "version"));
    if (own != null) {
      return own;
    }
    Element parent = Xml.child(project, "parent");
    String inherited = parent == null ? null : trimmed(Xml.childText(parent, "version"));
    return inherited == null ? "" : inherited;
  }

  private static String projectGroupId(Element project) {
    String own = trimmed(Xml.childText(project, "groupId"));
    if (own != null) {
      return own;
    }
    Element parent = Xml.child(project, "parent");
    String inherited = parent == null ? null : trimmed(Xml.childText(parent, "groupId"));
    return inherited == null ? "" : inherited;
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  /**
   * One row per (name, location).
   *
   * <p>A pom that names an artifact in both {@code dependencies} and {@code dependencyManagement}
   * through the same property has one line to edit; two rows would become two identical entries in
   * a bump payload and two identical lines in a commit message.
   */
  private static List<ParsedPin> dedupe(List<ParsedPin> pins) {
    Map<String, ParsedPin> byKey = new LinkedHashMap<>();
    for (ParsedPin pin : pins) {
      byKey.putIfAbsent(pin.name() + " " + pin.location(), pin);
    }
    return List.copyOf(byKey.values());
  }
}
