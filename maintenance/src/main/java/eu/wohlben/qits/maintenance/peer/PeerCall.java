package eu.wohlben.qits.maintenance.peer;

/**
 * A request as it went out — what a bump row records and what a failure is reproduced from.
 *
 * @param method GET or POST
 * @param url the absolute url, target base plus path
 * @param body the JSON body, or null for a GET
 */
public record PeerCall(String method, String url, String body) {}
