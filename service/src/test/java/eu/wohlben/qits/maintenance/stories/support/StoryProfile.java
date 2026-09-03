package eu.wohlben.qits.maintenance.stories.support;

import eu.wohlben.qits.maintenance.api.PackagedSurfaceIT;
import eu.wohlben.qits.maintenance.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>One launched qits-platform-maintenance for the whole story catalogue</b>, and every seam a
 * story moves, declared once.
 *
 * <p>A {@code @TestProfile} is what failsafe launches a process for, so two profiles would be two
 * services — two boots, two databases, two inventories. For <em>this</em> service that is the
 * sharpest form of the problem: the inventory is a STORE, and every story after the first one reads
 * what an earlier story's scan wrote. A second launch would have the bump stories composing a
 * payload from an inventory nobody filled. Every story class names this one,
 * {@code TokenValidationBootstrapIT} included; it is a story class like the others and it happens
 * to be the oldest.
 *
 * <p>It extends {@link PackagedSurfaceIT.PackagedUnderTarget} rather than copying it. What a
 * launched qits-platform-maintenance needs in order to boot at all — the platform's generic resource
 * triple, {@code QITS_RESOURCE_DB_*}, which is the shipped indirection rather than the datasource
 * keys — is one answer, written out at length over there, and a second copy of it would be a second
 * place for it to drift. What is added here is only the seams these stories move.
 *
 * <p><b>Every key is a RUNTIME key.</b> A packaged process takes its configuration as {@code -D}
 * arguments on a jar that was already built, so a build-time key would be silently ignored and these
 * stories would prove the opposite of what they say. The one inherited key of that kind —
 * {@code quarkus.scheduler.enabled} — is not build-time in this Quarkus (it is a RUN_TIME config
 * root), which is exactly why it has to be turned back ON below rather than left alone.
 *
 * <h2>What is moved, and why each one</h2>
 *
 * <ul>
 *   <li><b>The database</b>, on a name of this catalogue's own. {@link PackagedSurfaceIT} and this
 *       run are two launches of one artifact and a shared schema would mean each reading rows the
 *       other wrote — {@code mt_repository} counts, an active bump's lock. Its url travels through a
 *       system property rather than a static field, because a test profile is instantiated in more
 *       than one classloader and a field written by one copy is not the field another reads.
 *   <li><b>All eight peer urls</b>, and this is the inversion of what the parent does. Over there
 *       every target is a port nothing listens on, which is the right fixture for "a failure reaches
 *       a readable row with none of the suite's fakes involved". Here they are {@link StoryPeers}
 *       stand-ins that RECORD, because the outgoing half is where a scan's evidence is: what a
 *       manifest read looked like, which registry answered which pin, and what qits-ci was handed.
 *       Three of the eight are one stand-in behind three prefixes and two more behind two, exactly
 *       as {@code qits-artifacts} and {@code qits-platform-mirror} really mount them — which is what
 *       keeps a maven lookup from being answerable by the mirror's route.
 *   <li><b>{@code qits.auth.machine.required}</b> — THE GATE. The shipped tenant is
 *       {@code quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}}, so this one key is
 *       the difference between a service that validates machine bearers and one that does not, and
 *       no other suite in this repository turns it on at all.
 *   <li><b>{@code quarkus.oidc.auth-server-url}</b> — where the idp is. Discovery stays off and
 *       {@code jwks-path} stays {@code jwks}, joined onto this URL, so the packaged artifact is
 *       otherwise exactly what ships.
 *   <li><b>{@code qits.maintenance.call-timeout}</b>, bounded. A stand-in answers in microseconds
 *       and the outage arm closes the connection outright, so nothing here waits — but the shipped
 *       minute would turn a wrong assumption into a hung IT rather than a failed one.
 * </ul>
 *
 * <h2>The clock: one timer alive, and it is the one a story drives</h2>
 *
 * <p>The parent switches the scheduler off, which is right for a suite that starts all its own work.
 * This catalogue cannot: <b>a bump is finished by the sweep and by nothing else</b>. {@code
 * BumpService.dispatch} sends the trigger and returns with the row RUNNING; the poll that reads the
 * ci run and closes the row is only ever called from {@code BumpPollSchedule}. So the scheduler goes
 * back on — and then every other timer is silenced at its own shipped key, so that the only
 * background work that can draw an arrow is work a story is waiting for:
 *
 * <ul>
 *   <li>{@code scan.internal.cron} and {@code scan.external.cron} are set to {@code off}, which the
 *       scheduler's own {@code SchedulerUtils.isOff} understands — the trigger is not registered at
 *       all, rather than registered and skipped. {@code scan.enabled=false} says the same thing a
 *       second time, from the service's own side.
 *   <li>{@code bump.poll-interval} is a second rather than the shipped fifteen. The sweep is a no-op
 *       whenever no bump is active — it reads the store and queues nothing — so the only stories it
 *       can reach are the two that are holding a bump open on purpose.
 * </ul>
 *
 * <p><b>Two paths are therefore NOT covered by any story here, and that is a stated gap.</b> The
 * nightly internal bump ({@code BumpSchedule}) needs the cron this profile removes, and {@code
 * RestartRecovery} resuming a bump across a restart needs a second boot of one process. Both keep their coverage in {@code MaintenanceApiTest}, which drives the sweep by hand.
 * A story that waited out a six-hour cron would be indistinguishable from a story that hung.
 */
public class StoryProfile extends PackagedSurfaceIT.PackagedUnderTarget {

  /**
   * The audience this service enforces, and it is a LITERAL rather than a variable name:
   * {@code qits.auth.machine.audience=qits-platform-maintenance} is spelled out in
   * {@code application.properties} and {@code quarkus.oidc.token.audience} references it, so the
   * audience under test is the shipped one and there is no expression to feed. A deployment still
   * overrides it by environment.
   */
  public static final String AUDIENCE = "qits-platform-maintenance";

  /** This catalogue's own database on the one embedded postgres. */
  public static final String DATABASE = "maintenance_userflows_it";

  /**
   * And its own outbox database, for the same reason the store is its own: two launches of one
   * artifact must not share a claim ledger. The bus itself stays dark — the parent's
   * {@code qits.eventstream.enabled=false} is inherited — so nothing here dials, and no story draws
   * an event edge.
   */
  public static final String EVENTSTREAM_DATABASE = "maintenance_userflows_it_eventstream";

  /** Where the url is parked for whichever copy of this class is asked second. */
  private static final String URL_PROPERTY = "qits.test.userflows-it.db-url";

  private static final String EVENTSTREAM_URL_PROPERTY = "qits.test.userflows-it.eventstream-url";

  @Override
  public Map<String, String> getConfigOverrides() {
    MockIdp idp = MockIdp.ensureStarted();
    // The stand-ins are started HERE rather than in a story class, because the launched process
    // needs their addresses in its command line — which is built from exactly this map. `named` is
    // start-or-attach, so the second classloader to arrive gets the first one's ports.
    StoryCatalog.arm();

    // LinkedHashMap rather than Map.of: the order is the order this file explains them in, and a
    // reader diffing a launch command should find them in it.
    Map<String, String> overrides = new LinkedHashMap<>(super.getConfigOverrides());

    overrides.put("QITS_RESOURCE_DB_URL", databaseUrl());
    overrides.put("QITS_RESOURCE_EVENTSTREAM_URL", eventstreamUrl());

    overrides.put("qits.maintenance.targets.projects-url", url(StoryTarget.PROJECTS));
    overrides.put("qits.maintenance.targets.githost-url", url(StoryTarget.GITHOST));
    overrides.put("qits.maintenance.targets.ci-url", url(StoryTarget.CI));
    overrides.put(
        "qits.maintenance.registries.maven-url",
        url(StoryTarget.ARTIFACTS, StoryTarget.MAVEN_REGISTRY_PREFIX));
    overrides.put(
        "qits.maintenance.registries.npm-url",
        url(StoryTarget.ARTIFACTS, StoryTarget.NPM_REGISTRY_PREFIX));
    overrides.put(
        "qits.maintenance.registries.oci-url",
        url(StoryTarget.ARTIFACTS, StoryTarget.OCI_REGISTRY_PREFIX));
    overrides.put(
        "qits.maintenance.mirror.maven-url",
        url(StoryTarget.MIRROR, StoryTarget.MAVEN_MIRROR_PREFIX));
    overrides.put(
        "qits.maintenance.mirror.npm-url", url(StoryTarget.MIRROR, StoryTarget.NPM_MIRROR_PREFIX));
    overrides.put("qits.maintenance.call-timeout", "PT5S");

    // The clock. See the class comment: the sweep is what closes a bump, so the scheduler is on and
    // everything else it would run is removed at the key that removes it.
    overrides.put("quarkus.scheduler.enabled", "true");
    overrides.put("qits.maintenance.scan.enabled", "false");
    overrides.put("qits.maintenance.scan.internal.cron", "off");
    overrides.put("qits.maintenance.scan.external.cron", "off");
    // The sbom sweep is the third timer and it goes the same way as the two scans: no story drives
    // an ingest, and a sweep firing on the hour mid-catalogue would draw an artifacts arrow into
    // whichever story happened to be draining.
    overrides.put("qits.maintenance.sbom.sweep-cron", "off");
    overrides.put("qits.maintenance.bump.poll-interval", "1s");

    overrides.put("qits.auth.machine.required", "true");
    overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());

    return Map.copyOf(overrides);
  }

  private static String url(String peer) {
    return StoryPeers.named(peer).baseUrl();
  }

  private static String url(String peer, String prefix) {
    return StoryPeers.named(peer).baseUrl(prefix);
  }

  private static synchronized String databaseUrl() {
    String recorded = System.getProperty(URL_PROPERTY);
    if (recorded != null) {
      return recorded;
    }
    // localhost resolves for the launched process too — it is a child of this JVM on this host.
    String url = EmbeddedPg.url(DATABASE);
    System.setProperty(URL_PROPERTY, url);
    return url;
  }

  private static synchronized String eventstreamUrl() {
    String recorded = System.getProperty(EVENTSTREAM_URL_PROPERTY);
    if (recorded != null) {
      return recorded;
    }
    String url = EmbeddedPg.url(EVENTSTREAM_DATABASE);
    System.setProperty(EVENTSTREAM_URL_PROPERTY, url);
    return url;
  }
}
