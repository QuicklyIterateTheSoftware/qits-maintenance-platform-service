package eu.wohlben.qits.maintenance.dto;

/**
 * One pin of one repository, with the verdict on it.
 *
 * <p><b>{@code latest} and {@code pending} are not the same claim.</b> A pin can have a newer
 * version published and still not be pending — a prerelease is not offered to a released pin — and
 * a pin with no latest at all may be behind or up to date, which is what {@code latestError} is
 * there to say. Showing only "pending" would make an unreadable registry look like a green tick.
 *
 * @param manifestPath the file the pin is in
 * @param ecosystem maven, npm or docker
 * @param name the dependency
 * @param version what is pinned now
 * @param range the npm range from package.json, null elsewhere
 * @param kind INTERNAL or EXTERNAL
 * @param latest the newest published version, null when the lookup failed or never ran
 * @param latestError why there is no latest
 * @param pending whether a bump would move this line
 * @param group the group whose branch would carry it, null when nothing claims it
 * @param location where the version is set
 * @param scope always {@code DIRECT}, and it is a constant on purpose — see below
 */
public record PinDto(
    String manifestPath,
    String ecosystem,
    String name,
    String version,
    String range,
    String kind,
    String latest,
    String latestError,
    boolean pending,
    String group,
    String location,
    String scope) {

  /**
   * <b>Every pin is DIRECT, by definition, and the field says so rather than letting the client
   * infer it.</b>
   *
   * <p>The repository detail now serves two lists whose rows look alike on a page: pins, which a
   * manifest declares and a bump can edit, and transitives, which a released artifact contains and
   * nothing can edit. A reader with both in front of them needs the distinction spelled on the row,
   * not derived from which array it came out of — and a client that renders them in one table
   * (which is the point of showing both) has nothing else to render it from.
   *
   * <p>It is a constant because {@code mt_pin} holds direct pins and only those: a manifest holds
   * what its author wrote down. If a transitive ever became bumpable, it would become a pin with
   * this field saying otherwise, and the field is where that would be said.
   */
  public static final String DIRECT = "DIRECT";

  /** A pin with its scope filled in, which is the only way one is built. */
  public static PinDto direct(
      String manifestPath,
      String ecosystem,
      String name,
      String version,
      String range,
      String kind,
      String latest,
      String latestError,
      boolean pending,
      String group,
      String location) {
    return new PinDto(
        manifestPath,
        ecosystem,
        name,
        version,
        range,
        kind,
        latest,
        latestError,
        pending,
        group,
        location,
        DIRECT);
  }
}
