package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * <p><b>Expressions are resolved in the groupId and the artifactId too, not only the version.</b>
 * That is not a refinement: the platform's first live scan died on
 * {@code ${project.groupId}} reaching a URL builder verbatim, because only the version was ever
 * expanded. Every reactor pom on the platform writes its own modules that way.
 *
 * <p><b>Maven's built-in coordinates are known here</b> — {@code project.groupId},
 * {@code project.version}, {@code project.artifactId}, their {@code project.parent.*} siblings and
 * the deprecated {@code pom.*} and bare spellings — resolved from the pom's own coordinates, with a
 * module that declares no groupId or version inheriting its parent's, which is maven's own rule.
 *
 * <p><b>A property reference is resolved and REMEMBERED, not merely expanded.</b> The pin's
 * location becomes {@code property:<name>} rather than the dependency element, because that is the
 * line a bump has to change — rewriting the dependency's own version element would replace an
 * expression with a literal and quietly take the artifact off the shared property. A BUILT-IN is
 * never such a location: there is no line anywhere that holds {@code ${project.version}}.
 *
 * <p><b>Properties are looked up in this pom first, then in the ROOT pom.</b> That is the reactor's
 * own rule for a module whose parent is the root, which is the shape every repository here has.
 *
 * <p><b>A dependency with no version of its own is NOT a pin.</b> It takes one from a BOM, so there
 * is no line here to edit; recording it would put an entry in the inventory that nothing could ever
 * bump.
 */
public final class PomParser {

  /** How many times a value is re-expanded. A property may name a property; eight is far past any
   * real pom and it is what stops a pair that name each other. */
  private static final int MAX_EXPANSIONS = 8;

  private PomParser() {}

  /**
   * The pins of one pom.
   *
   * @param manifestPath the pom's repository-relative path, recorded on every pin
   * @param xml the file
   * @param rootProperties the root pom's DECLARED properties, for a module that inherits one; for
   *     the root itself pass an empty map — its own properties are read here anyway
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
    Map<String, String> ownDeclared = properties(project);

    // DECLARED first, BUILT-INS last and separate. The split is what keeps `property:` locations
    // honest: a value that came from ${project.version} has no line, so it must not be reported as
    // one, and the editedFile decision must not read a built-in as "the root pom declared it".
    Map<String, String> declared = new LinkedHashMap<>(rootProperties);
    declared.putAll(ownDeclared);
    Map<String, String> builtins = builtins(project);
    Map<String, String> all = new LinkedHashMap<>(declared);
    all.putAll(builtins);

    List<ParsedPin> pins = new ArrayList<>();
    Element parent = Xml.child(project, "parent");
    if (parent != null) {
      pin(manifestPath, rootManifestPath, ownDeclared, declared, builtins, all, parent, "parent")
          .ifPresent(pins::add);
    }
    for (Element dependency : dependencies(project)) {
      pin(manifestPath, rootManifestPath, ownDeclared, declared, builtins, all, dependency, "dependency")
          .ifPresent(pins::add);
    }
    Element management = Xml.child(project, "dependencyManagement");
    if (management != null) {
      for (Element dependency : dependencies(management)) {
        pin(manifestPath, rootManifestPath, ownDeclared, declared, builtins, all, dependency, "dependency")
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

  /**
   * Maven's built-in coordinate expressions, from this pom's own coordinates.
   *
   * <p>All the spellings, because poms in the wild carry all of them: the current
   * {@code project.*}, the long-deprecated {@code pom.*}, and the bare {@code version} /
   * {@code groupId} / {@code artifactId} that maven still resolves. A pom that writes one this does
   * not know leaves its pin UNRESOLVED, which is visible, rather than turning into a URL.
   */
  static Map<String, String> builtins(Element project) {
    Element parent = Xml.child(project, "parent");
    String parentGroupId = parent == null ? null : trimmed(Xml.childText(parent, "groupId"));
    String parentArtifactId = parent == null ? null : trimmed(Xml.childText(parent, "artifactId"));
    String parentVersion = parent == null ? null : trimmed(Xml.childText(parent, "version"));

    // A module that declares neither inherits both — maven's rule, and the shape of every reactor
    // module on this platform.
    String groupId = or(trimmed(Xml.childText(project, "groupId")), parentGroupId);
    String artifactId = trimmed(Xml.childText(project, "artifactId"));
    String version = or(trimmed(Xml.childText(project, "version")), parentVersion);

    Map<String, String> builtins = new LinkedHashMap<>();
    put(builtins, "groupId", groupId);
    put(builtins, "artifactId", artifactId);
    put(builtins, "version", version);
    put(builtins, "parent.groupId", parentGroupId);
    put(builtins, "parent.artifactId", parentArtifactId);
    put(builtins, "parent.version", parentVersion);
    for (String prefix : List.of("project.", "pom.")) {
      put(builtins, prefix + "groupId", groupId);
      put(builtins, prefix + "artifactId", artifactId);
      put(builtins, prefix + "version", version);
      put(builtins, prefix + "parent.groupId", parentGroupId);
      put(builtins, prefix + "parent.artifactId", parentArtifactId);
      put(builtins, prefix + "parent.version", parentVersion);
    }
    return builtins;
  }

  /**
   * One pom's own {@code groupId:artifactId}, with a module's inherited groupId filled in.
   *
   * <p>It is what {@link ManifestScanner} builds the reactor's membership from: a dependency on a
   * coordinate in that set is this repository depending on itself.
   */
  public static Optional<String> coordinate(String xml) {
    Element project = Xml.root(xml);
    if (project == null) {
      return Optional.empty();
    }
    Map<String, String> builtins = builtins(project);
    String groupId = builtins.get("project.groupId");
    String artifactId = builtins.get("project.artifactId");
    if (groupId == null || artifactId == null) {
      return Optional.empty();
    }
    return Optional.of(groupId + ":" + artifactId);
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

  private static Optional<ParsedPin> pin(
      String manifestPath,
      String rootManifestPath,
      Map<String, String> ownDeclared,
      Map<String, String> declared,
      Map<String, String> builtins,
      Map<String, String> all,
      Element element,
      String kind) {
    String rawGroupId = trimmed(Xml.childText(element, "groupId"));
    String rawArtifactId = trimmed(Xml.childText(element, "artifactId"));
    String rawVersion = trimmed(Xml.childText(element, "version"));
    if (rawGroupId == null || rawArtifactId == null || rawVersion == null) {
      return Optional.empty();
    }
    String groupId = expand(rawGroupId, all);
    String artifactId = expand(rawArtifactId, all);
    String version = expand(rawVersion, all);
    if (version.isBlank()) {
      return Optional.empty();
    }

    // A version taken from one of maven's own coordinates moves with this repository's release
    // train. It is a real dependency and it is recorded, but there is no line to bump. The other
    // half of that test — is this g:a a module of this reactor — needs the whole reactor, so it is
    // ManifestScanner's, applied afterwards.
    boolean reactorOwn = referencedNames(rawVersion).stream()
        .anyMatch(name -> !declared.containsKey(name) && builtins.containsKey(name));

    String property = declaredPropertyName(rawVersion, declared);
    String location =
        property != null ? "property:" + property : kind + ":" + groupId + ":" + artifactId;
    // Where the LINE is, which is not always where the dependency is: a property this pom does not
    // declare came from the root, and that is the file the step has to edit.
    String editedFile =
        property != null && !ownDeclared.containsKey(property) ? rootManifestPath : manifestPath;
    return Optional.of(
        new ParsedPin(
            Ecosystem.MAVEN,
            editedFile,
            groupId + ":" + artifactId,
            version,
            null,
            location,
            reactorOwn));
  }

  /**
   * The DECLARED property a whole value names, or null.
   *
   * <p>Null for a literal, and null for a built-in: {@code property:project.version} would send the
   * bump step looking for a line that exists in no file.
   */
  static String declaredPropertyName(String value, Map<String, String> declared) {
    String name = propertyName(value);
    return name != null && declared.containsKey(name) ? name : null;
  }

  /** A property reference to its name, or null when the value is not exactly one reference. */
  static String propertyName(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.startsWith("${") && trimmed.endsWith("}") && trimmed.indexOf('}') == trimmed.length() - 1
        && trimmed.length() > 3) {
      return trimmed.substring(2, trimmed.length() - 1);
    }
    return null;
  }

  /**
   * Substitutes every expression a map can answer, leaving the rest as it was written.
   *
   * <p><b>Anything left is left VISIBLE.</b> An unresolvable expression is not dropped and not
   * guessed at: the pin carries it, {@code MaintenanceConfig} reads it as UNRESOLVED, and nothing
   * asks a registry about it. That is the whole of the fix for the scan that died building
   * {@code .../central/${project/groupId}/...} — a value that never became a version must never
   * become a URL either.
   */
  static String expand(String value, Map<String, String> properties) {
    if (value == null || !value.contains("${")) {
      return value == null ? null : value.trim();
    }
    String current = value.trim();
    for (int pass = 0; pass < MAX_EXPANSIONS && current.contains("${"); pass++) {
      StringBuilder out = new StringBuilder(current.length());
      boolean changed = false;
      int index = 0;
      while (index < current.length()) {
        int start = current.indexOf("${", index);
        if (start < 0) {
          out.append(current, index, current.length());
          break;
        }
        int end = current.indexOf('}', start + 2);
        if (end < 0) {
          out.append(current, index, current.length());
          break;
        }
        out.append(current, index, start);
        String name = current.substring(start + 2, end);
        String replacement = properties.get(name);
        // A property whose value names itself is a pom's own loop, not something to unroll.
        if (replacement == null || replacement.contains("${" + name + "}")) {
          out.append(current, start, end + 1);
        } else {
          out.append(replacement);
          changed = true;
        }
        index = end + 1;
      }
      current = out.toString();
      if (!changed) {
        break;
      }
    }
    return current.trim();
  }

  /** Every name a value refers to, in order, whether or not anything can answer it. */
  static Set<String> referencedNames(String value) {
    Set<String> names = new LinkedHashSet<>();
    if (value == null) {
      return names;
    }
    int index = 0;
    while (true) {
      int start = value.indexOf("${", index);
      if (start < 0) {
        return names;
      }
      int end = value.indexOf('}', start + 2);
      if (end < 0) {
        return names;
      }
      names.add(value.substring(start + 2, end));
      index = end + 1;
    }
  }

  private static List<Element> dependencies(Element parent) {
    Element block = Xml.child(parent, "dependencies");
    return block == null ? List.of() : Xml.children(block, "dependency");
  }

  private static void put(Map<String, String> map, String key, String value) {
    if (value != null && !value.isBlank()) {
      map.put(key, value);
    }
  }

  private static String or(String value, String fallback) {
    return value != null ? value : fallback;
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
