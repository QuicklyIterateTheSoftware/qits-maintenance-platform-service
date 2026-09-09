package eu.wohlben.qits.maintenance.bump;

import eu.wohlben.qits.maintenance.control.ArtifactGraph;
import eu.wohlben.qits.maintenance.model.Ecosystem;
import eu.wohlben.qits.maintenance.pending.Change;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WHICH OWED BUMP GOES FIRST — the bottom of the dependency chain, always.
 *
 * <p><b>The failure this exists to stop is not load, it is waste.</b> A library's bump and its
 * consumer's bump asked for in the same breath means the consumer builds against the pin it is
 * about to be handed anyway, and needs a second bump the next night to pick up the release the
 * first one produced. Dispatching the deepest upstream first and recomputing afterwards collapses
 * those two nights into one hop that lands the real version.
 *
 * <p><b>The relation is read off the CHANGES, not off the whole graph.</b> A candidate is held back
 * by another candidate only when one of its own pending changes names something that candidate
 * publishes — which is exactly "the thing it is waiting on sits below it". A dependency on a
 * repository with nothing owed is not a reason to wait: that repository is already released and its
 * version is the one in {@code mt_latest}.
 *
 * <p><b>Two spellings of "who publishes this", because there are two kinds of edge.</b> A maven,
 * npm or image coordinate is matched through {@link ArtifactGraph#producers()}, which is the
 * released-artifact ledger. A GITLINK pin has no artifact at all — its {@code name} IS the
 * submodule's repository name, which is the same string the catalog lists — so it is matched by
 * name directly, and only for that ecosystem, so a package that happens to be called like a
 * repository cannot invent an edge.
 *
 * <p><b>A cycle degrades to a pick, never to a stall.</b> {@code ArtifactGraph} says plainly that
 * cycles occur and that the graph is not guaranteed acyclic, so "every candidate is blocked" is a
 * state this has to have an answer for: the least-blocked candidate goes, the caller says so in the
 * log, and the next tick asks again against a graph one release smaller. The alternative — waiting
 * for an unblocked candidate that cannot exist — is a night in which nothing is bumped at all.
 */
public final class BumpOrder {

  private BumpOrder() {}

  /**
   * One repository owed a bump, with the changes that bump would carry.
   *
   * @param repository the repository name, as the catalog spells it
   * @param group the group whose branch it would go on
   * @param changes the group's pending changes, computed this tick
   */
  public record Candidate(String repository, String group, List<Change> changes) {}

  /**
   * The candidate to send now.
   *
   * @param candidate the pick
   * @param blockedBy the owed upstreams it still has, empty on an ordinary pick
   * @param cycleBroken whether it was picked despite being blocked, because everything was
   */
  public record Pick(Candidate candidate, Set<String> blockedBy, boolean cycleBroken) {}

  /**
   * The next viable bump: the first candidate nothing else owed sits below, in listing order.
   *
   * @param candidates every repository owed a bump this tick, in a stable order
   * @param producers {@link ArtifactGraph#producers()} — coordinate to publishing repository
   */
  public static Optional<Pick> next(List<Candidate> candidates, Map<String, String> producers) {
    if (candidates == null || candidates.isEmpty()) {
      return Optional.empty();
    }
    Set<String> owed = new LinkedHashSet<>();
    for (Candidate candidate : candidates) {
      owed.add(candidate.repository());
    }

    Candidate leastBlocked = null;
    Set<String> leastBlockedBy = null;
    for (Candidate candidate : candidates) {
      Set<String> upstreams = owedUpstreams(candidate, producers, owed);
      if (upstreams.isEmpty()) {
        return Optional.of(new Pick(candidate, Set.of(), false));
      }
      if (leastBlockedBy == null || upstreams.size() < leastBlockedBy.size()) {
        leastBlocked = candidate;
        leastBlockedBy = upstreams;
      }
    }
    // Every one of them waits on another one of them: a cycle, and somebody has to move first.
    return Optional.of(new Pick(leastBlocked, Set.copyOf(leastBlockedBy), true));
  }

  /**
   * The candidates this one is waiting on — the owed repositories that publish something it is
   * about to bump to.
   */
  public static Set<String> owedUpstreams(
      Candidate candidate, Map<String, String> producers, Set<String> owed) {
    Set<String> upstreams = new LinkedHashSet<>();
    for (Change change : candidate.changes()) {
      String producer = producerOf(change, producers, owed);
      if (producer == null || producer.equals(candidate.repository()) || !owed.contains(producer)) {
        continue;
      }
      upstreams.add(producer);
    }
    return upstreams;
  }

  private static String producerOf(Change change, Map<String, String> producers, Set<String> owed) {
    if (change == null || change.name() == null) {
      return null;
    }
    String published = producers.get(ArtifactGraph.producerKey(change.ecosystem(), change.name()));
    if (published != null) {
      return published;
    }
    // A submodule is named by the repository it is, and no artifact row is involved at all.
    boolean gitlink = Ecosystem.GITLINK.wireName().equals(change.ecosystem());
    return gitlink && owed.contains(change.name()) ? change.name() : null;
  }
}
