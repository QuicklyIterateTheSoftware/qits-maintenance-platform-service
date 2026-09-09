package eu.wohlben.qits.maintenance.bump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.pending.Change;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * WHICH OWED BUMP GOES FIRST.
 *
 * <p>A plain unit test and not a {@code @QuarkusTest}: the ordering is a function of two maps, and
 * the only way to write the interesting cases — a three-hop chain, a cycle, a dependency on
 * somebody who is NOT owed — is to state the graph rather than build an estate that happens to have
 * one.
 */
class BumpOrderTest {

  private static final String LIB = "qits-eventstream-javalib";
  private static final String SERVICE = "qits-ci-service";
  private static final String WRAPPER = "qits-qits";

  /** {@code (ecosystem, name)} to the repository that publishes it, as {@code ArtifactGraph} answers it. */
  private static final Map<String, String> PRODUCERS =
      Map.of(
          ArtifactGraph.producerKey("maven", "eu.wohlben.qits:qits-eventstream"), LIB,
          ArtifactGraph.producerKey("docker", "qits/qits-ci"), SERVICE);

  private static BumpOrder.Candidate candidate(String repository, Change... changes) {
    return new BumpOrder.Candidate(repository, "dependencies", List.of(changes));
  }

  private static Change on(String ecosystem, String name) {
    return new Change(ecosystem, "pom.xml", name, "1", "2", "property");
  }

  /**
   * THE WASTE THIS EXISTS TO STOP. The library and the service that consumes it are both owed a
   * bump; sending the service first builds it against the pin it is about to be handed anyway.
   */
  @Test
  void theDeepestUpstreamGoesFirstWhateverTheListingOrderIs() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            // The consumer is FIRST in listing order, which is exactly the arrangement a loop over
            // the inventory would have got wrong.
            candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            candidate(LIB, on("maven", "io.quarkus.platform:quarkus-bom")));

    BumpOrder.Pick pick = BumpOrder.next(candidates, PRODUCERS).orElseThrow();

    assertEquals(LIB, pick.candidate().repository(), "the library is what nothing else waits below");
    assertFalse(pick.cycleBroken());
  }

  /**
   * A dependency on a repository with NOTHING owed is not a reason to wait: that release already
   * happened, and its version is the one the change is moving to.
   */
  @Test
  void anUpstreamThatIsNotOwedABumpBlocksNothing() {
    List<BumpOrder.Candidate> candidates =
        List.of(candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")));

    BumpOrder.Pick pick = BumpOrder.next(candidates, PRODUCERS).orElseThrow();

    assertEquals(SERVICE, pick.candidate().repository());
    assertTrue(pick.blockedBy().isEmpty());
  }

  /**
   * <b>A GITLINK HAS NO ARTIFACT AT ALL</b>, and its name IS the repository — the same string the
   * catalog lists. The wrapper waiting on a submodule is the estate's commonest bottom-of-chain
   * edge and the artifact ledger cannot see it.
   */
  @Test
  void aSubmoduleEdgeIsMatchedByNameBecauseNoArtifactCarriesIt() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(WRAPPER, on(Ecosystem.GITLINK.wireName(), SERVICE)),
            candidate(SERVICE, on("maven", "io.quarkus.platform:quarkus-bom")));

    BumpOrder.Pick pick = BumpOrder.next(candidates, PRODUCERS).orElseThrow();

    assertEquals(SERVICE, pick.candidate().repository(), "the wrapper carries the submodule, not the other way round");
  }

  /** And only for GITLINK: a package that happens to be called like a repository invents no edge. */
  @Test
  void aPackageNamedLikeARepositoryIsNotASubmodule() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(WRAPPER, on("npm", SERVICE)),
            candidate(SERVICE, on("maven", "io.quarkus.platform:quarkus-bom")));

    BumpOrder.Pick pick = BumpOrder.next(candidates, PRODUCERS).orElseThrow();

    assertEquals(WRAPPER, pick.candidate().repository(), "nothing blocks the wrapper, so listing order decides");
  }

  /**
   * <b>A CYCLE DEGRADES TO A PICK, NEVER TO A STALL.</b> {@code ArtifactGraph} says cycles occur
   * and the graph is not guaranteed acyclic, so the answer to "everything is blocked" has to be a
   * repository and a WARN — a night in which nothing is bumped is the worse outcome.
   */
  @Test
  void aCycleStillDispatchesSomething() {
    List<BumpOrder.Candidate> candidates =
        List.of(
            candidate(SERVICE, on("maven", "eu.wohlben.qits:qits-eventstream")),
            candidate(LIB, on("docker", "qits/qits-ci")));

    BumpOrder.Pick pick = BumpOrder.next(candidates, PRODUCERS).orElseThrow();

    assertTrue(pick.cycleBroken(), "both wait on the other, and one of them has to move");
    assertFalse(pick.blockedBy().isEmpty(), "and the log says what it was waiting on");
  }

  @Test
  void nothingOwedIsNoPick() {
    assertEquals(Optional.empty(), BumpOrder.next(List.of(), PRODUCERS));
  }
}
