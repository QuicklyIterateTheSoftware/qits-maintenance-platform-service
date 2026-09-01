package eu.wohlben.qits.maintenance.model;

/**
 * How far one released artifact's bill of materials has got.
 *
 * <p><b>MISSING is not a failure and is deliberately terminal.</b> qits-artifacts stores an SBOM
 * for artifacts released since that route existed and holds nothing for the ones released before
 * it, so a 404 is the ORDINARY answer during the rollout — for most coordinates it is also the
 * permanent one. Retrying it would be a schedule asking the same question about the same immutable
 * version for ever; the next release of that artifact brings its own row, and a person who knows
 * better can re-ingest one by hand.
 */
public enum SbomStatus {
  /** The row exists and the document has not been read yet — the outbox state. */
  PENDING,

  /** The components and edges recorded against this artifact are that document's. */
  INGESTED,

  /** qits-artifacts has no document for this coordinate. Nothing retries it. */
  MISSING,

  /** The document could not be read, and {@code sbom_error} says why. */
  FAILED;

  /** The status for a wire or column value, defaulting to PENDING for a word this build lacks. */
  public static SbomStatus of(String value) {
    if (value == null) {
      return PENDING;
    }
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return PENDING;
    }
  }
}
