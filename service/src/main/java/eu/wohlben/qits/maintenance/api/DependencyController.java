package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.control.Inventory;
import eu.wohlben.qits.maintenance.dto.DependencyDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The inventory read the other way round: one dependency, everyone who pins it.
 *
 * <p><b>This is the question a release is followed by.</b> "Who is still on eventstream
 * 2026.8.11" has no answer anywhere else on the platform — every repository knows its own pins and
 * nothing knows them all — and it is the whole reason the inventory is a store rather than a page
 * that asks the git host on each load.
 *
 * <p>The glob is the same two-wildcard form a group's {@code deps} entry uses, so an operator can
 * paste one from a repository's configuration and see exactly what it claims.
 */
@Path("/dependencies")
@Produces(MediaType.APPLICATION_JSON)
public class DependencyController {

  @Inject Inventory inventory;

  @GET
  @Operation(summary = "Dependencies matching a glob, with every pin of each")
  @APIResponse(responseCode = "200", description = "The dependencies")
  @RolesAllowed({"qits:admin", "qits:system"})
  public List<DependencyDto> dependencies(@QueryParam("name") @DefaultValue("*") String name) {
    return inventory.dependencies(name);
  }
}
