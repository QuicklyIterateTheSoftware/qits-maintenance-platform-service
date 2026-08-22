package eu.wohlben.qits.maintenance.config;

import eu.wohlben.qits.maintenance.model.PinKind;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The policy this service runs on: what counts as internal, whether the clock may scan, whether a
 * scan may bump, and which environment's CI applies one.
 *
 * <p><b>Nothing here decides what a manifest says or what a registry holds</b> — those are read.
 * What lives here is the handful of decisions a platform makes about its own maintenance, each one
 * an environment variable rather than a rebuild.
 */
@ApplicationScoped
public class MaintenanceConfig {

  @ConfigProperty(name = "qits.maintenance.environment")
  String environment;

  @ConfigProperty(name = "qits.maintenance.scan.enabled")
  boolean scanEnabled;

  @ConfigProperty(name = "qits.maintenance.bump.enabled")
  boolean bumpEnabled;

  @ConfigProperty(name = "qits.maintenance.bump.auto")
  boolean bumpAuto;

  @ConfigProperty(name = "qits.maintenance.internal.maven-groups")
  List<String> internalMavenGroups;

  @ConfigProperty(name = "qits.maintenance.internal.npm-scopes")
  List<String> internalNpmScopes;

  @ConfigProperty(name = "qits.maintenance.internal.image-prefixes")
  List<String> internalImagePrefixes;

  /** Which environment's qits-ci applies a bump. Recorded on every bump row. */
  public String environment() {
    return environment;
  }

  /** Whether the CLOCK may start a scan. A manual scan ignores it — a person is the trigger. */
  public boolean scanEnabled() {
    return scanEnabled;
  }

  /**
   * Whether a bump may be dispatched at all.
   *
   * <p>False stops the button as well as the schedule, and the UI still shows what is pending: a
   * platform that wants to watch the changes for a week before letting anything push a branch sets
   * this and reads the inventory.
   */
  public boolean bumpEnabled() {
    return bumpEnabled;
  }

  /** Whether a SCHEDULED scan asks for a bump of every group that has pending changes. */
  public boolean bumpAuto() {
    return bumpAuto;
  }

  /**
   * Whether this platform publishes the named dependency.
   *
   * <p><b>A name rule, not a lookup.</b> Asking a registry whether it holds a package would make
   * every scan a round trip per dependency, and a registry that is briefly down would reclassify
   * half the inventory as external — which would then be looked up against Maven Central, where
   * {@code eu.wohlben.qits} does not exist, and every internal pin would report no latest at all.
   */
  public PinKind kindOf(eu.wohlben.qits.maintenance.model.Ecosystem ecosystem, String name) {
    if (name == null) {
      return PinKind.EXTERNAL;
    }
    String value = name.trim();
    boolean internal =
        switch (ecosystem) {
          case MAVEN -> matchesGroup(value);
          case NPM -> startsWithAny(value, internalNpmScopes, true);
          case DOCKER -> startsWithAny(value, internalImagePrefixes, false);
        };
    return internal ? PinKind.INTERNAL : PinKind.EXTERNAL;
  }

  /**
   * A maven name is {@code groupId:artifactId}, and a configured group matches the whole groupId or
   * a parent of it — {@code eu.wohlben.qits} claims {@code eu.wohlben.qits.something} and does not
   * claim {@code eu.wohlben.qitsy}.
   */
  private boolean matchesGroup(String name) {
    int colon = name.indexOf(':');
    String groupId = colon < 0 ? name : name.substring(0, colon);
    for (String configured : internalMavenGroups) {
      String prefix = configured.trim();
      if (prefix.isEmpty()) {
        continue;
      }
      if (groupId.equals(prefix) || groupId.startsWith(prefix + ".")) {
        return true;
      }
    }
    return false;
  }

  /**
   * @param caseInsensitive npm names are lower case by rule but a manifest can spell one otherwise;
   *     an image name is case-sensitive and a prefix that differs in case is a different registry
   *     path
   */
  private static boolean startsWithAny(
      String value, List<String> prefixes, boolean caseInsensitive) {
    String candidate = caseInsensitive ? value.toLowerCase(Locale.ROOT) : value;
    for (String configured : prefixes) {
      String prefix = configured.trim();
      if (prefix.isEmpty()) {
        continue;
      }
      String compare = caseInsensitive ? prefix.toLowerCase(Locale.ROOT) : prefix;
      if (candidate.startsWith(compare)) {
        return true;
      }
    }
    return false;
  }
}
