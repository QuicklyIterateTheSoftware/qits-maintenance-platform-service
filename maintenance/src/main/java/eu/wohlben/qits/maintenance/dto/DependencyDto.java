package eu.wohlben.qits.maintenance.dto;

import java.time.Instant;
import java.util.List;

/**
 * One dependency and everyone who pins it — the answer to "who is still on eventstream 2026.8.11".
 *
 * @param ecosystem maven, npm or docker
 * @param name the dependency
 * @param latest the newest published version, null when the lookup failed or never ran
 * @param checkedAt when that was read
 * @param error why there is no latest
 * @param pins every pin of it, across every repository
 */
public record DependencyDto(
    String ecosystem,
    String name,
    String latest,
    Instant checkedAt,
    String error,
    List<DependencyPinDto> pins) {

  /**
   * One repository's pin of the dependency above.
   *
   * @param repository which repository
   * @param version what it pins
   * @param manifestPath which of its files
   * @param pending whether a bump would move this line
   */
  public record DependencyPinDto(
      String repository, String version, String manifestPath, boolean pending) {}
}
