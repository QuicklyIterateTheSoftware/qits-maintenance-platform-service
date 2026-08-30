package eu.wohlben.qits.maintenance.stories.support;

import eu.wohlben.qits.servicemock.idp.MockIdp;
import io.restassured.specification.RequestSpecification;

/**
 * The two kinds of caller this service has, and how a story hands each of them to a request.
 *
 * <h2>A person is a pair of headers</h2>
 *
 * <p>{@link #operator(RequestSpecification)} sends {@code X-Qits-User} and {@code X-Qits-Roles},
 * which is what the platform edge asserts for a logged-in operator and the whole of this service's
 * relationship with human authentication — it authenticates no person itself. That is not a
 * shortcut around the bearer: the OIDC tenant this catalogue turns on is <b>bearer-only</b>, so a
 * request carrying no {@code Authorization} header is never challenged by it and falls through to
 * qits-auth-core's header mechanism, exactly as it does behind the edge. Using it is what lets the
 * scan and bump stories say "an operator" honestly rather than dressing a person up as a machine.
 *
 * <h2>A machine is a bearer this run's idp minted</h2>
 *
 * <p>{@link #machineToken()} mints a token signed by {@link MockIdp}'s generated keypair, addressed
 * to this service's audience and carrying {@code qits:system} in {@code groups}. Every token is
 * minted <b>fresh per call and never cached</b>: a helper that handed the same string to two stories
 * would make {@code assertNotLeaked} a weaker claim than it reads as.
 *
 * <h2>Why both, in one catalogue</h2>
 *
 * <p>Because both really reach this service, and the shape of the guard is that <b>they reach
 * exactly the same surface</b>. Every route is {@code @RolesAllowed({"qits:admin", "qits:system"})}
 * — an operator presses <i>Bump now</i> in a browser and a scheduled machine may ask for the same
 * thing, so a machine-only guard would lock the operator out of the button this service exists to
 * offer. There is no ceiling here that one of the two does not reach, which is this repository's
 * shape rather than an omission; what bounds a credential is the role SET, and the refusal stories
 * are where that is proved.
 */
public final class StoryIdentities {

  /** The header the edge names the logged-in person in. */
  public static final String USER_HEADER = "X-Qits-User";

  /** The header the edge asserts that person's roles in, comma-separated. */
  public static final String ROLES_HEADER = "X-Qits-Roles";

  /** The human role: every route here, including the two that queue work. */
  public static final String ADMIN_ROLE = "qits:admin";

  /** The machine role a platform peer holds — and the only role this service acts OUTWARD with. */
  public static final String MACHINE_ROLE = "qits:system";

  /** A real platform role that is neither of the two every route here names. */
  public static final String READER_ROLE = "qits:reader";

  /** How the diagram names a person at the console. */
  public static final String OPERATOR = "an operator";

  /** …and a platform peer holding a bearer. */
  public static final String PEER = "a platform service";

  /** …a caller who presented no credential at all. */
  public static final String ANONYMOUS = "an unauthenticated caller";

  /** …and one who authenticated perfectly and covers nothing. */
  public static final String WRONG_ROLE = "a caller with the wrong role";

  /** The operator, by name — the value the edge would carry in {@code X-Qits-User}. */
  private static final String OPERATOR_NAME = "alice";

  private StoryIdentities() {}

  /** {@code given()} with the two headers the edge asserts for a logged-in operator. */
  public static RequestSpecification operator(RequestSpecification request) {
    return request.header(USER_HEADER, OPERATOR_NAME).header(ROLES_HEADER, ADMIN_ROLE);
  }

  /** The same person, with a role this service's routes have never heard of. */
  public static RequestSpecification withRole(RequestSpecification request, String role) {
    return request.header(USER_HEADER, OPERATOR_NAME).header(ROLES_HEADER, role);
  }

  /** A freshly minted platform-peer bearer: this service's audience, {@code qits:system}. */
  public static String machineToken() {
    return MockIdp.attach()
        .token()
        .subject("a-platform-service")
        .audience(StoryProfile.AUDIENCE)
        .groups(MACHINE_ROLE)
        .mint();
  }

  /** {@code given()} carrying a bearer the story already holds — it has to pin it as not leaked. */
  public static RequestSpecification bearer(RequestSpecification request, String token) {
    return request.header("Authorization", "Bearer " + token);
  }
}
