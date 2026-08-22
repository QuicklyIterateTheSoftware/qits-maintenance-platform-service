package eu.wohlben.qits.maintenance.manifest;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The little DOM this context needs, and the safety settings it needs them with.
 *
 * <p><b>XML-aware, never a regular expression.</b> A pom is read to find WHERE a version is set, and
 * a line-based reader cannot tell a {@code <version>} inside {@code <dependencyManagement>} from one
 * inside a plugin, or a commented-out block from a live one. The bump step edits by the location
 * this parser produced, so a wrong location is a wrong edit in somebody else's repository.
 *
 * <p><b>External entities are off and so is the DOCTYPE.</b> Every document here comes off another
 * service over HTTP; a parser that resolved entities would fetch whatever a repository's pom told
 * it to.
 *
 * <p><b>Namespace-UNaware on purpose.</b> A pom declares the maven namespace and a
 * {@code maven-metadata.xml} does not, and the elements are looked up by their plain names in both.
 * Namespace awareness would make the same lookup work for one and fail for the other.
 */
public final class Xml {

  private Xml() {}

  /** The document's root element, or null when it does not parse. */
  public static Element root(String xml) {
    if (xml == null || xml.isBlank()) {
      return null;
    }
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      factory.setExpandEntityReferences(false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      Document document =
          builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
      return document.getDocumentElement();
    } catch (Exception e) {
      return null;
    }
  }

  /** The first direct child element of that name, or null. */
  public static Element child(Element parent, String name) {
    List<Element> found = children(parent, name);
    return found.isEmpty() ? null : found.get(0);
  }

  /** Every DIRECT child element of that name — never a descendant, which is what keeps a plugin's
   * {@code <dependencies>} out of the project's. */
  public static List<Element> children(Element parent, String name) {
    List<Element> found = new ArrayList<>();
    if (parent == null) {
      return found;
    }
    NodeList nodes = parent.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      if (node.getNodeType() == Node.ELEMENT_NODE && node.getNodeName().equals(name)) {
        found.add((Element) node);
      }
    }
    return found;
  }

  /** The text of the first direct child of that name, or null. */
  public static String childText(Element parent, String name) {
    Element element = child(parent, name);
    return element == null ? null : text(element);
  }

  /** An element's own text, children's text included, comments excluded. */
  public static String text(Node node) {
    StringBuilder text = new StringBuilder();
    NodeList children = node.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      short type = child.getNodeType();
      if (type == Node.TEXT_NODE || type == Node.CDATA_SECTION_NODE) {
        text.append(child.getNodeValue());
      } else if (type == Node.ELEMENT_NODE) {
        text.append(text(child));
      }
    }
    return text.toString();
  }
}
