package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.error.BadRequestException;
import eu.wohlben.qits.maintenance.model.ScanScope;
import eu.wohlben.qits.maintenance.scan.ScanService;
import eu.wohlben.qits.maintenance.scan.ScanTrigger;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * Re-read the manifests and refresh the latest versions — the button beside the two crons.
 *
 * <p><b>202 and no waiting.</b> A scan of the whole catalog is one git-host read per repository
 * plus a registry lookup per dependency; it is minutes, and an HTTP request is the wrong place to
 * hold it.
 *
 * <p><b>The id is the QUEUED WORK's and nothing stores it.</b> A scan's outcome is the repository
 * rows it wrote — which is what {@code GET /repositories} shows — so there is no scan row to fetch
 * and no route that would take this id. It is in the answer because a client that queued work is
 * owed the name of what it queued, and because it is what the log line says.
 *
 * <p><b>A manual scan never bumps.</b> {@code qits.maintenance.bump.auto} is about the schedule:
 * pressing Scan asks what is out of date, and pressing Bump asks for a branch.
 */
@Path("/scans")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ScanController {

  @Inject ScanService scans;

  /**
   * The request body of a scan.
   *
   * @param scope INTERNAL, EXTERNAL or ALL; absent means ALL
   * @param repository one repository's name, or absent for the whole catalog
   */
  public record StartScanRequest(String scope, String repository) {

    /** What a 202 answers with. */
    public record Response(UUID id) {}
  }

  @POST
  @Operation(summary = "Re-scan the manifests and refresh the latest versions")
  @APIResponse(responseCode = "202", description = "Queued")
  @APIResponse(responseCode = "400", description = "Unknown scope")
  @RolesAllowed({"qits:admin", "qits:system"})
  public Response scan(StartScanRequest request) {
    String requested = request == null ? null : request.scope();
    ScanScope scope =
        requested == null || requested.isBlank()
            ? ScanScope.ALL
            : ScanScope.of(requested)
                .orElseThrow(
                    () ->
                        new BadRequestException(
                            "scope must be INTERNAL, EXTERNAL or ALL, not '" + requested + "'"));
    String repository =
        request == null || request.repository() == null || request.repository().isBlank()
            ? null
            : request.repository().trim();
    UUID id = scans.request(scope, repository, ScanTrigger.MANUAL);
    return Response.status(Response.Status.ACCEPTED)
        .entity(new StartScanRequest.Response(id))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
