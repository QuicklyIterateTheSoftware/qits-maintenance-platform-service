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
    String location) {}
