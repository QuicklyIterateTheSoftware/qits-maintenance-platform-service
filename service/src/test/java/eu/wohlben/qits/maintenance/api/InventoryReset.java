package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.entity.MtArtifact;
import eu.wohlben.qits.maintenance.entity.MtArtifactComponent;
import eu.wohlben.qits.maintenance.entity.MtArtifactEdge;
import eu.wohlben.qits.maintenance.entity.MtBranch;
import eu.wohlben.qits.maintenance.entity.MtBump;
import eu.wohlben.qits.maintenance.entity.MtGroup;
import eu.wohlben.qits.maintenance.entity.MtLatest;
import eu.wohlben.qits.maintenance.entity.MtPin;
import eu.wohlben.qits.maintenance.entity.MtRepository;
import eu.wohlben.qits.maintenance.entity.MtScan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Empties the store between test methods.
 *
 * <p><b>The suite shares one database across a class</b> — Flyway's {@code clean-at-start} runs per
 * Quarkus start, not per test — and the bump rows are exactly the kind that must not leak: an
 * active bump holds its (repository, group) lock, so the second test of a class would be answered
 * 409 by the first test's row and read as a broken refusal rather than a dirty fixture.
 *
 * <p>A write the previous test's worker thread makes after this ran is a no-op: every store write
 * looks its row up first and returns when it is gone.
 */
@ApplicationScoped
public class InventoryReset {

  @Transactional
  public void clear() {
    // The graph first: mt_artifact_component and mt_artifact_edge are the only rows in this schema
    // with a real foreign key, and it points at mt_artifact.
    MtArtifactEdge.deleteAll();
    MtArtifactComponent.deleteAll();
    MtArtifact.deleteAll();
    MtBump.deleteAll();
    MtBranch.deleteAll();
    MtScan.deleteAll();
    MtPin.deleteAll();
    MtGroup.deleteAll();
    MtLatest.deleteAll();
    MtRepository.deleteAll();
  }
}
