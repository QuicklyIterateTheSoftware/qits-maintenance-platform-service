package eu.wohlben.qits.maintenance.manifest;

import eu.wohlben.qits.maintenance.model.Ecosystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the {@code FROM} lines of one Dockerfile.
 *
 * <p><b>The location is a LINE NUMBER</b>, because that is the only address a Dockerfile has. There
 * is no key, no element and no property: a file with two stages built from the same image has two
 * lines, and each is bumped on its own.
 *
 * <p><b>A digest pin is not read.</b> {@code FROM image@sha256:…} names a content address rather
 * than a version, and there is no ordering over content addresses — a "newer" one is a decision
 * somebody else made.
 *
 * <p><b>A tagless {@code FROM} is not read either.</b> It means {@code :latest}, which is a moving
 * reference; there is nothing to compare and nothing to write.
 *
 * <p><b>Which images survive is not decided here.</b> This parser reports every FROM it can read
 * and the scanner keeps only the ones the internal image prefixes claim — v1 does not order
 * external base tags, because tag order across vendors is a later decision.
 */
public final class DockerParser {

  private DockerParser() {}

  /**
   * The image pins of one Dockerfile.
   *
   * @param manifestPath the file's repository-relative path
   * @param dockerfile the file
   */
  public static List<ParsedPin> parse(String manifestPath, String dockerfile) {
    if (dockerfile == null || dockerfile.isBlank()) {
      return List.of();
    }
    List<ParsedPin> pins = new ArrayList<>();
    String[] lines = dockerfile.split("\r?\n", -1);
    for (int index = 0; index < lines.length; index++) {
      String line = lines[index].trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] words = line.split("\\s+");
      if (!words[0].toUpperCase(Locale.ROOT).equals("FROM")) {
        continue;
      }
      String reference = reference(words);
      if (reference == null) {
        continue;
      }
      // A build-arg reference resolves at build time, not here; there is no version to record.
      if (reference.contains("$")) {
        continue;
      }
      if (reference.contains("@")) {
        continue;
      }
      int tagSeparator = tagSeparator(reference);
      if (tagSeparator < 0) {
        continue;
      }
      String image = reference.substring(0, tagSeparator);
      String tag = reference.substring(tagSeparator + 1);
      if (image.isEmpty() || tag.isEmpty()) {
        continue;
      }
      pins.add(
          new ParsedPin(
              Ecosystem.DOCKER, manifestPath, image, tag, null, "line:" + (index + 1)));
    }
    return List.copyOf(pins);
  }

  /**
   * The image reference of a FROM line: the first word that is neither the verb nor a flag, and the
   * stage alias after {@code AS} is dropped.
   */
  private static String reference(String[] words) {
    for (int i = 1; i < words.length; i++) {
      String word = words[i];
      if (word.startsWith("--")) {
        continue;
      }
      return word;
    }
    return null;
  }

  /**
   * Where the tag starts, or -1 when there is none.
   *
   * <p>The colon has to be AFTER the last slash: {@code mirror.dev.localhost:8080/quay/image} has a
   * colon in its registry host, and reading that as a tag would name an image nobody wrote.
   */
  private static int tagSeparator(String reference) {
    int lastSlash = reference.lastIndexOf('/');
    int colon = reference.lastIndexOf(':');
    return colon > lastSlash ? colon : -1;
  }
}
