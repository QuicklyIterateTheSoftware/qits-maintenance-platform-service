package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.control.Trains;
import eu.wohlben.qits.maintenance.dto.TrainDto;
import eu.wohlben.qits.maintenance.dto.TrainSummaryDto;
import eu.wohlben.qits.maintenance.error.BadRequestException;
import eu.wohlben.qits.maintenance.error.NoSuchTrainException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The release trains — where each of this platform's releases got to.
 *
 * <p>Served under {@code /maintenance/api/trains}; the {@code /maintenance/api} prefix is {@code
 * quarkus.rest.path} and is never spelled in a controller.
 *
 * <p><b>Read-only, all three routes.</b> Nothing here queues work, so there is no 202 and no
 * {@code WorkQueue}: a train is written by the bus listener that heard the release and moved by the
 * evaluation and the sweep behind it. What a person can do with a stalled journey is act on the
 * repository that owes it, which is somebody else's route.
 *
 * <p><b>One train per answer, and the journey is stitched by the client.</b> See {@link Trains} for
 * why the fold is not made here.
 *
 * <p>Every route takes the same pair of roles as the rest of this API — {@code qits:admin} for a
 * person through the gateway's forward-auth headers, {@code qits:system} for a machine — and the
 * annotation is on every METHOD rather than on the class, because a method-level {@code
 * @RolesAllowed} REPLACES a class-level one and a mixture is how one route ends up open.
 */
@Path("/trains")
@Produces(MediaType.APPLICATION_JSON)
public class TrainController {

  /** The index's page size, and its ceiling. A summary row is small; the nodes are not on it. */
  static final int DEFAULT_LIMIT = 50;

  static final int MAX_LIMIT = 200;

  @Inject Trains trains;

  @GET
  @Operation(summary = "The newest release trains, of one repository or of all of them")
  @APIResponse(responseCode = "200", description = "The trains, newest first")
  @RolesAllowed({"qits:admin", "qits:system"})
  public List<TrainSummaryDto> trains(
      @QueryParam("repository") String repository,
      @QueryParam("limit") @DefaultValue("" + DEFAULT_LIMIT) int limit) {
    return trains.trains(repository, Math.clamp(limit, 1, MAX_LIMIT));
  }

  /**
   * The train of one released {@code (repository, version)} — the cross-link a release request
   * follows.
   *
   * <p><b>A QUERY RESOLVER RATHER THAN A PATH ROUTE, so {@code /{id}} stays unambiguous.</b> A
   * version is not a safe path segment on its own — it can carry a slash in another ecosystem's
   * spelling — and a two-segment {@code /by-release/{repository}/{version}} would sit beside a
   * one-segment {@code /{id}} with a literal that has to win by JAX-RS' sorting rules rather than by
   * anything a reader can see. Two query parameters have neither problem.
   *
   * <p>Both are required: a by-release lookup missing half its key is a caller bug, and answering
   * "the newest train of that repository" instead would be this service guessing which release was
   * meant.
   */
  @GET
  @Path("/by-release")
  @Operation(summary = "The release train of one released repository and version")
  @APIResponse(responseCode = "200", description = "The train")
  @APIResponse(responseCode = "400", description = "Both repository and version are required")
  @APIResponse(responseCode = "404", description = "That release has no train")
  @RolesAllowed({"qits:admin", "qits:system"})
  public TrainDto byRelease(
      @QueryParam("repository") String repository, @QueryParam("version") String version) {
    String named = trimmed(repository);
    String released = trimmed(version);
    if (named == null || released == null) {
      throw new BadRequestException("a by-release lookup names both a repository and a version");
    }
    return trains.byRelease(named, released);
  }

  /**
   * One train: the station, what it released, and every adopter it is owed.
   *
   * <p>An id that is not a uuid is a 404 like any other unknown train — a malformed id and an absent
   * one are the same question from the caller's side, and a 500 about parsing would be the answer to
   * neither.
   */
  @GET
  @Path("/{id}")
  @Operation(summary = "One release train with every expected adopter")
  @APIResponse(responseCode = "200", description = "The train")
  @APIResponse(responseCode = "404", description = "No such train")
  @RolesAllowed({"qits:admin", "qits:system"})
  public TrainDto train(@PathParam("id") String id) {
    UUID trainId;
    try {
      trainId = UUID.fromString(id);
    } catch (IllegalArgumentException notAnId) {
      throw new NoSuchTrainException(id);
    }
    return trains.train(trainId);
  }

  private static String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
