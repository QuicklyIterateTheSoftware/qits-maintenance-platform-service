package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.bump.BumpService;
import eu.wohlben.qits.maintenance.control.Inventory;
import eu.wohlben.qits.maintenance.dto.RepositoryDetailDto;
import eu.wohlben.qits.maintenance.dto.RepositoryDto;
import eu.wohlben.qits.maintenance.model.BumpTrigger;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The inventory: repositories, their pins, and the button that turns one group's pending changes
 * into a branch.
 *
 * <p>Served under {@code /maintenance/api/repositories} — the {@code /maintenance/api} prefix is
 * {@code quarkus.rest.path}, not spelled here, so this class carries only its own noun.
 *
 * <p><b>Every route accepts the same pair of roles</b>, {@code qits:admin} (a person, through the
 * gateway's forward-auth headers) and {@code qits:system} (a machine, through a bearer validated
 * against qits-platform-idp). A bump is asked for by an operator in a browser and could as well be
 * asked for by a machine; a machine-only guard would lock the operator out of the button this
 * service exists to offer. There is no anonymous route here.
 */
@Path("/repositories")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RepositoryController {

  @Inject Inventory inventory;

  @Inject BumpService bumps;

  /** What a 202 answers with — the id to poll. */
  public record AcceptedResponse(UUID id) {}

  @GET
  @Operation(summary = "Every repository in the inventory, with its groups and what is pending")
  @APIResponse(responseCode = "200", description = "The repositories")
  @RolesAllowed({"qits:admin", "qits:system"})
  public List<RepositoryDto> repositories() {
    return inventory.repositories();
  }

  /**
   * One repository with every pin it holds, and the verdict on each.
   *
   * <p>404 when the inventory has no such row — which covers both "never scanned" and "not in the
   * catalog", because neither has anything to show.
   */
  @GET
  @jakarta.ws.rs.Path("/{name}")
  @Operation(summary = "One repository with every pin it holds")
  @APIResponse(responseCode = "200", description = "The repository")
  @APIResponse(responseCode = "404", description = "No such repository in the inventory")
  @RolesAllowed({"qits:admin", "qits:system"})
  public RepositoryDetailDto repository(@PathParam("name") String name) {
    return inventory.repository(name);
  }

  /**
   * Asks for one group's pending changes to be put on its branch, and does NOT wait.
   *
   * <p><b>202 with the id.</b> A bump is a CI run in somebody else's pipeline — a clone, an edit, a
   * push — and an HTTP request is the wrong place to hold that. The id is what {@code GET
   * /bumps/{id}} takes.
   *
   * <p><b>409 while a bump of that (repository, group) is active</b>, and again when bumping is
   * switched off. Two runs writing one branch would make the second a non-ff rejection at best.
   *
   * <p><b>A group with nothing pending still answers 202</b> and the row ends NOTHING_TO_DO. The
   * inventory can be seconds out of date, so refusing here would be refusing on the strength of a
   * cache — and the honest answer is a row that says what the run found.
   */
  @POST
  @jakarta.ws.rs.Path("/{name}/groups/{group}/bumps")
  @Operation(summary = "Put this group's pending changes on its maintenance branch")
  @APIResponse(responseCode = "202", description = "Requested; poll GET /bumps/{id}")
  @APIResponse(responseCode = "404", description = "No such repository, or no such group")
  @APIResponse(responseCode = "409", description = "One is already active, or bumping is disabled")
  @RolesAllowed({"qits:admin", "qits:system"})
  public Response bump(@PathParam("name") String name, @PathParam("group") String group) {
    UUID id = bumps.request(name, group, BumpTrigger.MANUAL);
    return Response.status(Response.Status.ACCEPTED)
        .entity(new AcceptedResponse(id))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
