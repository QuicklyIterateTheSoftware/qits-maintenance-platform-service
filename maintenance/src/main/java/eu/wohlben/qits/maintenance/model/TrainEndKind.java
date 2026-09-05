package eu.wohlben.qits.maintenance.model;

/**
 * The END a consumer adopts a release through — which is what decides what "adopted" even means for
 * that node, and where the evidence for it is read.
 *
 * <p><b>One consumer can hold two of these at once and they settle independently</b>, which is why
 * a node is keyed by {@code (train, consumer, end kind)} rather than by {@code (train, consumer)}:
 * the service that distributes a daemon may also pin that daemon's image in a Dockerfile, and its
 * manifest bump says nothing about whether its config moved.
 */
public enum TrainEndKind {

  /**
   * An ordinary manifest pin: a pom property, a package.json range, a Dockerfile {@code FROM}.
   *
   * <p><b>The default, and the only one derivable from this service's own tables.</b> There is a
   * line to edit, {@code mt_pin} names it, and the bump machinery already knows how — so adoption is
   * that line moving, read from the inventory this service maintains anyway.
   */
  LINKED,

  /**
   * The released thing is an OCI image and the consumer is an application whose DEPLOYMENT
   * configuration names it.
   *
   * <p><b>No repository this service scans holds that pin.</b> It lives in qits-configuration's
   * ImagePins map and is read over HTTP — which is why a node of this kind is never placed by a
   * spawn: a spawn runs inside the bus's claim transaction, and an outbound call from there turns a
   * slow peer into an event redelivered for ever. The config-pin sweep places them afterwards.
   */
  CONFIG_IMAGE_PIN,

  /**
   * The released thing is a DAEMON and the consumer is the service that hands it out.
   *
   * <p>Also not a manifest line: the adopting service records which daemon build it distributes, and
   * adoption is that record moving.
   */
  DAEMON_PIN;

  /** The stored spelling — the enum's own name. */
  public String wireName() {
    return name();
  }

  /** The end kind for a stored value, defaulting to {@link #LINKED} for a word this build lacks. */
  public static TrainEndKind of(String value) {
    if (value == null) {
      return LINKED;
    }
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return LINKED;
    }
  }
}
