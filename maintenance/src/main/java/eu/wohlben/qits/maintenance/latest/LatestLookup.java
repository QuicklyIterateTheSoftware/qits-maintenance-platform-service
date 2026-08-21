package eu.wohlben.qits.maintenance.latest;

/**
 * What one registry said about one dependency.
 *
 * <p><b>A failure is a result, not an exception.</b> A scan asks about hundreds of dependencies and
 * one registry hiccup must cost one row rather than the run — and the row is WRITTEN, with the
 * sentence, because "we could not find out" has to look different in the UI from "you are up to
 * date". Those two are indistinguishable if a failed lookup simply leaves the old value standing.
 *
 * @param latest the newest version, or null when the lookup failed
 * @param sourceUrl the url that was read, so a surprising answer can be reproduced by hand
 * @param error the sentence, or null when the lookup succeeded
 */
public record LatestLookup(String latest, String sourceUrl, String error) {

  public static LatestLookup found(String latest, String sourceUrl) {
    return new LatestLookup(latest, sourceUrl, null);
  }

  public static LatestLookup failed(String sourceUrl, String error) {
    return new LatestLookup(null, sourceUrl, error);
  }

  public boolean ok() {
    return error == null && latest != null;
  }
}
