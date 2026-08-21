package eu.wohlben.qits.maintenance.peer;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a peer said, or why it said nothing.
 *
 * <p><b>A transport failure is an answer here, not an exception.</b> A scan reads every repository
 * in the catalog and one unreachable git host must not end the other seventy; a caller that had to
 * catch would put half its outcomes on a path nobody reads. Everything — a name that does not
 * resolve, a timeout, a body that is not JSON — comes back as one of these carrying the sentence.
 *
 * @param httpStatus the peer's status code, or null when the call never got one
 * @param body the response body as text, bounded by {@link PeerClient}
 * @param json the same body parsed, or null when it was not JSON
 * @param headers the response headers, lower-cased names, first value only
 * @param error the transport failure, or null when the call completed
 */
public record PeerAnswer(
    Integer httpStatus, String body, JsonNode json, Map<String, String> headers, String error) {

  /** Whether the peer answered 2xx. */
  public boolean ok() {
    return httpStatus != null && httpStatus >= 200 && httpStatus < 300;
  }

  /** Whether the peer answered 404 — for the git host, "no such revision, or no such path". */
  public boolean notFound() {
    return httpStatus != null && httpStatus == 404;
  }

  /** One response header, by a case-insensitive name. */
  public Optional<String> header(String name) {
    if (headers == null || name == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(headers.get(name.toLowerCase(Locale.ROOT)));
  }

  /** The call could not be made at all, or was answered with something other than 2xx or 404. */
  public String failure() {
    if (error != null) {
      return error;
    }
    return "HTTP " + httpStatus;
  }
}
