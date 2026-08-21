package eu.wohlben.qits.maintenance.peer;

/**
 * One call and its answer, together — the pair a bump row is written from.
 *
 * <p>They travel as one because a record of a request with no answer beside it cannot be read, and
 * an answer with no request beside it cannot be checked.
 */
public record PeerExchange(PeerCall call, PeerAnswer answer) {}
