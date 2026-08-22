package eu.wohlben.qits.maintenance.latest;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.maintenance.manifest.Xml;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.peer.PeerAnswer;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * "What is the newest version of this dependency" — one question, five addresses.
 *
 * <p><b>Which address is decided by the pin's KIND, not by the ecosystem alone.</b> An internal
 * maven artifact lives in qits-artifacts' hosted repository and an external one is read through
 * qits-platform-mirror's Central cache; asking the wrong one for either answers 404 and reports no
 * latest at all, which reads exactly like "up to date".
 *
 * <p><b>Docker is internal-only in v1.</b> Ordering tags across vendors — a temurin tag beside an
 * alpine one — is a later decision, and the mirror's OCI proxies would answer with an upstream's
 * whole tag history with no rule to rank it by. An external image pin is recorded in the inventory
 * and never looked up.
 *
 * <p><b>Nothing here throws.</b> A registry that is down costs one row's {@code error} column.
 */
@ApplicationScoped
public class LatestResolver {

  /** How many pages of an OCI tag listing are followed. A page is 1000 tags; ten is far past any
   * image on this platform, and a bound is what keeps one runaway repository from being an
   * unbounded read. */
  private static final int MAX_TAG_PAGES = 10;

  private static final int TAG_PAGE_SIZE = 1000;

  @Inject PeerClient peers;

  /** The newest version of one dependency, or why there is none. */
  public LatestLookup resolve(Ecosystem ecosystem, PinKind kind, String name) {
    return switch (ecosystem) {
      case MAVEN -> maven(kind, name);
      case NPM -> npm(kind, name);
      case DOCKER -> docker(kind, name);
    };
  }

  /** Whether this service looks a pin of that shape up at all. */
  public boolean resolvable(Ecosystem ecosystem, PinKind kind) {
    return ecosystem != Ecosystem.DOCKER || kind == PinKind.INTERNAL;
  }

  /**
   * {@code maven-metadata.xml}, and the versions list rather than the {@code <latest>} element.
   *
   * <p>The document's own {@code <latest>} is whatever the last deploy wrote and can name a
   * SNAPSHOT; {@code <release>} is closer but is absent on a repository that has only ever held
   * prereleases. Ranking the published list is one rule for both, and it is the same rule a
   * resolver would apply.
   */
  private LatestLookup maven(PinKind kind, String name) {
    int colon = name.indexOf(':');
    if (colon <= 0 || colon == name.length() - 1) {
      return LatestLookup.failed(null, "'" + name + "' is not a groupId:artifactId");
    }
    String groupPath = name.substring(0, colon).replace('.', '/');
    String artifactId = name.substring(colon + 1);
    PeerTarget target = kind == PinKind.INTERNAL ? PeerTarget.MAVEN_REGISTRY : PeerTarget.MAVEN_MIRROR;
    String path = "/" + groupPath + "/" + artifactId + "/maven-metadata.xml";
    PeerExchange exchange = peers.get(target, path);
    String url = exchange.call().url();
    PeerAnswer answer = exchange.answer();
    if (!answer.ok()) {
      return LatestLookup.failed(url, answer.failure());
    }
    Element metadata = Xml.root(answer.body());
    if (metadata == null) {
      return LatestLookup.failed(url, "the metadata document did not parse");
    }
    Element versioning = Xml.child(metadata, "versioning");
    Element versions = versioning == null ? null : Xml.child(versioning, "versions");
    if (versions == null) {
      return LatestLookup.failed(url, "the metadata document carries no versions");
    }
    List<String> published = new ArrayList<>();
    for (Element version : Xml.children(versions, "version")) {
      String value = Xml.text(version).trim();
      if (!value.isEmpty()) {
        published.add(value);
      }
    }
    return pick(Ecosystem.MAVEN, published, url);
  }

  /**
   * The packument, and the versions it lists rather than {@code dist-tags.latest} alone.
   *
   * <p>{@code dist-tags.latest} is a POINTER a publisher moves and can point at anything — a
   * patch of an old line, deliberately. The version set is the fact; the tag is included in the
   * candidates so a registry that lists nothing useful still answers.
   */
  private LatestLookup npm(PinKind kind, String name) {
    PeerTarget target = kind == PinKind.INTERNAL ? PeerTarget.NPM_REGISTRY : PeerTarget.NPM_MIRROR;
    PeerExchange exchange = peers.get(target, "/" + encodePackage(name));
    String url = exchange.call().url();
    PeerAnswer answer = exchange.answer();
    if (!answer.ok()) {
      return LatestLookup.failed(url, answer.failure());
    }
    JsonNode packument = answer.json();
    if (packument == null || !packument.isObject()) {
      return LatestLookup.failed(url, "the packument did not parse");
    }
    Set<String> published = new LinkedHashSet<>();
    JsonNode versions = packument.get("versions");
    if (versions != null && versions.isObject()) {
      Iterator<String> names = versions.fieldNames();
      while (names.hasNext()) {
        published.add(names.next());
      }
    }
    JsonNode distTags = packument.get("dist-tags");
    if (distTags != null && distTags.isObject()) {
      JsonNode latest = distTags.get("latest");
      if (latest != null && latest.isTextual()) {
        published.add(latest.asText());
      }
    }
    if (published.isEmpty()) {
      return LatestLookup.failed(url, "the packument lists no versions");
    }
    return pick(Ecosystem.NPM, published, url);
  }

  /** The OCI tag listing, paged. */
  private LatestLookup docker(PinKind kind, String name) {
    if (kind != PinKind.INTERNAL) {
      return LatestLookup.failed(
          null, "external base images are not ordered in v1; only " + "internal images are");
    }
    Set<String> tags = new LinkedHashSet<>();
    String last = null;
    String url = null;
    for (int page = 0; page < MAX_TAG_PAGES; page++) {
      String path =
          "/" + name + "/tags/list?n=" + TAG_PAGE_SIZE
              + (last == null ? "" : "&last=" + URLEncoder.encode(last, StandardCharsets.UTF_8));
      PeerExchange exchange = peers.get(PeerTarget.OCI_REGISTRY, path);
      url = exchange.call().url();
      PeerAnswer answer = exchange.answer();
      if (!answer.ok()) {
        return LatestLookup.failed(url, answer.failure());
      }
      JsonNode body = answer.json();
      if (body == null || !body.hasNonNull("tags") || !body.get("tags").isArray()) {
        return LatestLookup.failed(url, "the tag listing carries no tags array");
      }
      int before = tags.size();
      String lastOfPage = null;
      for (JsonNode tag : body.get("tags")) {
        if (tag.isTextual()) {
          tags.add(tag.asText());
          lastOfPage = tag.asText();
        }
      }
      // A short page is the end, and a page that added nothing new is a registry that is not
      // paging the way the header says — either way, stop rather than ask forever.
      if (tags.size() == before || lastOfPage == null || tags.size() - before < TAG_PAGE_SIZE) {
        break;
      }
      last = lastOfPage;
    }
    if (tags.isEmpty()) {
      return LatestLookup.failed(url, "the image has no tags");
    }
    return pick(Ecosystem.DOCKER, tags, url);
  }

  private static LatestLookup pick(Ecosystem ecosystem, java.util.Collection<String> published, String url) {
    Optional<String> highest = VersionOrder.highest(ecosystem, published);
    return highest
        .map(latest -> LatestLookup.found(latest, url))
        .orElseGet(() -> LatestLookup.failed(url, "nothing published there is a readable version"));
  }

  /**
   * A scoped package's slash is encoded.
   *
   * <p>The registry route takes {@code @scope%2fname} as ONE path segment; an unencoded slash would
   * be read as a repository plus a package and answer 404.
   */
  static String encodePackage(String name) {
    return name.replace("/", "%2f");
  }
}
