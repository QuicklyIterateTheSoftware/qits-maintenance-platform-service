package eu.wohlben.qits.maintenance.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the six keys
 * a deployment would supply for the TWO datasources this application opens — {@code maintenance}
 * (the domain jar's) and {@code eventstream} (the event bus jar's outbox and claim ledger).
 *
 * <p>It is a config source rather than six lines in {@code
 * src/test/resources/application.properties} because the port is chosen at run time — the instance
 * takes a free one, so nothing can be written down ahead of the JVM that starts it.
 *
 * <p>The ordinal sits above application.properties (250) so this wins over both jars' shipped
 * defaults — which under test are unresolvable {@code ${QITS_RESOURCE_*}} expressions — and it is
 * registered through {@code META-INF/services}, which is how a config source joins a Quarkus
 * application without being a bean.
 *
 * <p>It supplies the DATASOURCE keys rather than the {@code QITS_RESOURCE_*} triples the shipped
 * defaults expand: the packaged-artifact IT in this module takes the triples, because there the
 * point is to exercise the shipped expressions themselves. Here the point is only to have a
 * database.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /** This module's database on the shared instance — the other module names its own. */
  private static final String DATABASE = "maintenance_svc";

  /**
   * The event bus's outbox and claim tables, on a name of their own.
   *
   * <p>Deliberately not {@code eventstream_test} — that is the qits-eventstream library's own
   * suite's database, and a consumer must not be able to mean it.
   *
   * <p>It is here because joining that jar turned this deployable into one that opens a SECOND
   * datasource: {@code qits.eventstream.enabled=false} under {@code %test} stops publishing,
   * sweeping and dialling, and stops none of the connecting and migrating Quarkus does at boot. So
   * the outbox gets a database here or the whole suite fails to start.
   */
  private static final String EVENTSTREAM_DATABASE = "maintenance_svc_eventstream";

  private static final String PREFIX = "quarkus.datasource.maintenance.";

  private static final String EVENTSTREAM_PREFIX = "quarkus.datasource.eventstream.";

  private final Map<String, String> values =
      Map.of(
          PREFIX + "jdbc.url", EmbeddedPg.url(DATABASE),
          PREFIX + "username", EmbeddedPg.USER,
          PREFIX + "password", EmbeddedPg.PASSWORD,
          EVENTSTREAM_PREFIX + "jdbc.url", EmbeddedPg.url(EVENTSTREAM_DATABASE),
          EVENTSTREAM_PREFIX + "username", EmbeddedPg.USER,
          EVENTSTREAM_PREFIX + "password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
