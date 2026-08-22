package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.maintenance.model.Ecosystem;

/**
 * One pin as a parser read it, before the inventory decides whether it is internal.
 *
 * @param ecosystem which parser produced it
 * @param manifestPath the file it was read from, repository-relative
 * @param name the dependency in its own ecosystem's spelling
 * @param version what is pinned right now — for npm the LOCK's resolved version
 * @param range the npm range from package.json, null for maven and docker
 * @param location where the version is SET, so the bump step edits the right line
 */
public record ParsedPin(
    Ecosystem ecosystem,
    String manifestPath,
    String name,
    String version,
    String range,
    String location) {

  /**
   * A version this service will not compare or send.
   *
   * <p>The same character set the bump step validates against before a version reaches a shell and
   * a docker reference. Refused here as well, so an implausible pin never becomes a payload the
   * step has to reject.
   */
  public boolean plausible() {
    return version != null && !version.isBlank() && version.matches("[0-9A-Za-z._+-]+");
  }
}
