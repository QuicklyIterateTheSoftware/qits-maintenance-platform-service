package eu.wohlben.qits.maintenance.model;

/**
 * How much of the inventory one scan refreshes.
 *
 * <p><b>The scope is about the LATEST lookups, not about the manifests.</b> Every scan re-reads
 * every manifest — that is one git-host read per repository and it is cheap — and then asks the
 * registries only about the half the scope names. Internal releases land many times a day and are
 * one hop away; external ones are a daily question against somebody else's index.
 */
public enum ScanScope {
  /** Refresh the latest version of every dependency this platform publishes. */
  INTERNAL,

  /** Refresh everybody else's. */
  EXTERNAL,

  /** Both. */
  ALL;

  /** Whether this scope refreshes the latest of a pin of that kind. */
  public boolean covers(PinKind kind) {
    return switch (this) {
      case ALL -> true;
      case INTERNAL -> kind == PinKind.INTERNAL;
      case EXTERNAL -> kind == PinKind.EXTERNAL;
    };
  }

  /** The scope for a name, case-insensitively, or empty. */
  public static java.util.Optional<ScanScope> of(String name) {
    if (name == null) {
      return java.util.Optional.empty();
    }
    for (ScanScope value : values()) {
      if (value.name().equalsIgnoreCase(name.trim())) {
        return java.util.Optional.of(value);
      }
    }
    return java.util.Optional.empty();
  }
}
