package eu.wohlben.qits.maintenance;

import eu.wohlben.qits.archrules.DatasourceBaselineRules;
import org.junit.jupiter.api.Test;

/**
 * The `maintenance` datasource carries the platform's resilience baseline: the patient driver,
 * validation at borrow, and a 15s acquisition timeout. The rule reads the config rather than the
 * code, and it names each missing line.
 *
 * <p>It lives in {@code service/} because this module's classpath is the deployable's whole config —
 * the datasource itself is declared in the {@code maintenance} jar, and a service that adds a
 * second one is judged here without anything being added to this class.
 *
 * <p>This service writes while a CI run is in flight on another host: a pool that handed out a dead
 * connection during a postgres cutover would lose the record of a branch that was already pushed.
 */
class DatasourceBaselineTest {

  @Test
  void everyPostgresDatasourceCarriesTheBaseline() {
    DatasourceBaselineRules.assertBaseline();
  }
}
