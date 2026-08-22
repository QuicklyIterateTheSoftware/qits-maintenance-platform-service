package eu.wohlben.qits.maintenance.latest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.model.PinKind;
import eu.wohlben.qits.maintenance.peer.PeerCall;
import eu.wohlben.qits.maintenance.peer.PeerClient;
import eu.wohlben.qits.maintenance.peer.PeerExchange;
import eu.wohlben.qits.maintenance.peer.PeerTarget;
import org.junit.jupiter.api.Test;

/**
 * THE FAILURE THAT ENDED THE FIRST LIVE SCAN, and the rule that came out of it.
 *
 * <p>One dependency of 49 repositories carried an unresolved expression, it reached
 * {@code URI.create}, the {@code IllegalArgumentException} came out of the resolver, past the
 * scanner, past the run, and the scan row stayed RUNNING for ever. A lookup is one question about
 * one dependency: it may not be able to end a scan.
 *
 * <p>No Quarkus here — the resolver's own error handling is a property of the class, and a stub
 * client is all it takes to prove it.
 */
class LatestResolverTest {

  /** A client that answers however a test needs it to, at no port. */
  private static class Stub extends PeerClient {

    private final RuntimeException thrown;

    Stub(RuntimeException thrown) {
      this.thrown = thrown;
    }

    @Override
    public String url(PeerTarget target, String path) {
      return "http://stub" + path;
    }

    @Override
    public PeerExchange get(PeerTarget target, String path) {
      if (thrown != null) {
        throw thrown;
      }
      return new PeerExchange(
          new PeerCall("GET", url(target, path), null),
          new eu.wohlben.qits.maintenance.peer.PeerAnswer(
              503, "", null, java.util.Map.of(), null));
    }
  }

  private static LatestResolver resolver(PeerClient peers) {
    LatestResolver resolver = new LatestResolver();
    resolver.peers = peers;
    return resolver;
  }

  @Test
  void aNameThatIsStillAnExpressionIsAnErrorRowAndNeverARequest() {
    // ${project.groupId}:qits-artifacts-artifacts — the exact shape that died live. Nothing is
    // asked, so there is no url to malform.
    Stub peers = new Stub(new IllegalStateException("no request may be made for an expression"));
    LatestLookup lookup =
        resolver(peers)
            .resolve(Ecosystem.MAVEN, PinKind.INTERNAL, "${project.groupId}:qits-artifacts-artifacts");
    assertFalse(lookup.ok());
    assertNull(lookup.latest());
    assertTrue(lookup.error().contains("is not a name this service can address"));
  }

  @Test
  void everyCharacterARealNameCarriesIsStillAllowed() {
    // A maven colon, an npm scope's at-sign and slash, an image path's slashes, a registry host's
    // port. Refusing any of these would make the guard the new outage.
    LatestResolver resolver = resolver(new Stub(null));
    for (String name :
        new String[] {
          "eu.wohlben.qits:qits-eventstream",
          "@angular/core",
          "qits/build-images/maven-base",
          "mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image"
        }) {
      LatestLookup lookup = resolver.resolve(Ecosystem.MAVEN, PinKind.INTERNAL, name);
      assertFalse(
          lookup.error().contains("is not a name this service can address"),
          name + " must be addressable");
    }
  }

  @Test
  void anExceptionInOneLookupBecomesThatDependencysErrorAndNothingMore() {
    LatestLookup lookup =
        resolver(new Stub(new IllegalArgumentException("Illegal character in path at index 58")))
            .resolve(Ecosystem.MAVEN, PinKind.EXTERNAL, "io.quarkus:quarkus-arc");
    assertFalse(lookup.ok());
    assertTrue(lookup.error().startsWith("the lookup failed: "));
    assertTrue(lookup.error().contains("Illegal character"));
  }

  @Test
  void aNonTwoHundredIsTheDependencysErrorToo() {
    LatestLookup lookup =
        resolver(new Stub(null)).resolve(Ecosystem.MAVEN, PinKind.EXTERNAL, "g:a");
    assertFalse(lookup.ok());
    assertEquals("HTTP 503", lookup.error());
  }

  @Test
  void theRepositorysOwnArtifactsAreNeverLookedUpAtAll() {
    LatestResolver resolver = resolver(new Stub(null));
    assertFalse(resolver.resolvable(Ecosystem.MAVEN, PinKind.REACTOR));
    assertFalse(resolver.resolvable(Ecosystem.MAVEN, PinKind.UNRESOLVED));
    assertTrue(resolver.resolvable(Ecosystem.MAVEN, PinKind.INTERNAL));
    // v1 orders no external base image tags.
    assertFalse(resolver.resolvable(Ecosystem.DOCKER, PinKind.EXTERNAL));
    assertTrue(resolver.resolvable(Ecosystem.DOCKER, PinKind.INTERNAL));
  }
}
