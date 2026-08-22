package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.maintenance.model.Ecosystem;

/**
 * One pin as a parser read it, before the inventory decides what can be done with it.
 *
 * @param ecosystem which parser produced it
 * @param manifestPath the file it was read from, repository-relative
 * @param name the dependency in its own ecosystem's spelling
 * @param version what is pinned right now — for npm the LOCK's resolved version
 * @param range the npm range from package.json, null for maven and docker
 * @param location where the version is SET, so the bump step edits the right line
 * @param reactorOwn whether this is the repository's OWN artifact — its version came from one of
 *     maven's own coordinates ({@code ${project.version}} and its spellings), or its
 *     {@code groupId:artifactId} is a module of this same reactor. Either way no line anywhere
 *     holds the version and it moves with this repository's release train.
 */
public record ParsedPin(
    Ecosystem ecosystem,
    String manifestPath,
    String name,
    String version,
    String range,
    String location,
    boolean reactorOwn) {

  /** npm and docker pins: no expressions, and no reactor of their own. */
  public static ParsedPin of(
      Ecosystem ecosystem,
      String manifestPath,
      String name,
      String version,
      String range,
      String location) {
    return new ParsedPin(ecosystem, manifestPath, name, version, range, location, false);
  }

  /**
   * Whether an expression survived parsing.
   *
   * <p>The pin is still recorded — a person reading a repository should see what it actually
   * wrote — but nothing is asked of a registry about it. The platform's first live scan died
   * turning one of these into a URL.
   */
  public boolean unresolved() {
    return contains(name) || contains(version);
  }

  /**
   * A version this service will not compare or send.
   *
   * <p>The same character set the bump step validates against before a version reaches a shell and
   * a docker reference. Refused here as well, so an implausible pin never becomes a payload the
   * step has to reject.
   */
  /** The same pin, told that it is (or is not) one of this reactor's own modules. */
  public ParsedPin withReactorOwn(boolean value) {
    return value == reactorOwn
        ? this
        : new ParsedPin(ecosystem, manifestPath, name, version, range, location, value);
  }

  public boolean plausible() {
    return version != null && !version.isBlank() && version.matches("[0-9A-Za-z._+-]+");
  }

  private static boolean contains(String value) {
    return value != null && value.contains("${");
  }
}
