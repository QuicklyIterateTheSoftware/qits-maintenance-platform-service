package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.bump.BumpDispatcher;
import eu.wohlben.qits.maintenance.control.Inventory;
import eu.wohlben.qits.maintenance.dto.BumpDto;
import eu.wohlben.qits.maintenance.dto.BumpWindowDto;
import eu.wohlben.qits.maintenance.error.NoBumpWindowException;
import eu.wohlben.qits.maintenance.error.NoSuchBumpException;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * The bump log — what was asked of qits-ci, and what came of it.
 *
 * <p>Its own root rather than a child of {@code /repositories/{name}/…}: a bump id is unique on its
 * own and a bump's address is a thing an operator pastes into a message. Two paths for one row
 * would mean two links for one bump.
 */
@Path("/bumps")
@Produces(MediaType.APPLICATION_JSON)
public class BumpController {

  /** The listing's page size, and its ceiling: a bump row carries its whole change list. */
  static final int DEFAULT_LIMIT = 20;

  static final int MAX_LIMIT = 200;

  @Inject Inventory inventory;

  @Inject BumpDispatcher dispatcher;

  /**
   * The dispatch window: what the 02:00 cron opens and what the tick hands bumps out inside.
   *
   * <p><b>Its own three verbs rather than a field on some settings object</b>, because the window is
   * one fact with one lifecycle and the three things anybody wants to do to it are: see whether
   * tonight is running, start one now, and stop one that is running away.
   *
   * <p><b>Why a door exists at all.</b> Before it, the only way to make the estate bump outside
   * 02:00 was to override {@code bump.internal.cron} in the deployment config and redeploy — which
   * restarts the very service whose window you are trying to open, and leaves an override behind
   * that somebody has to remember to delete. Both live incidents on this ticket were investigated
   * that way and it is not a thing to do twice. Pressing Bump on every repository by hand is the
   * other option, and that is the 02:00 stampede this whole change exists to stop.
   */
  @GET
  @jakarta.ws.rs.Path("/window")
  @Operation(summary = "The bump dispatch window, if one is open")
  @APIResponse(responseCode = "200", description = "The window")
  @APIResponse(responseCode = "404", description = "There is no window")
  @RolesAllowed({"qits:admin", "qits:system"})
  public BumpWindowDto window() {
    return inventory
        .bumpWindow(Instant.now())
        .orElseThrow(NoBumpWindowException::new);
  }

  /**
   * Opens one now — the cron's job, by hand, for the same length.
   *
   * <p>Opening while one is already open replaces it, which is what "open a window now" plainly
   * means and is the same upsert the cron does. It dispatches nothing itself: the next tick, within
   * {@code bump.poll-interval}, asks the three gates exactly as it would at 02:00.
   */
  @POST
  @jakarta.ws.rs.Path("/window")
  @Operation(summary = "Open a bump dispatch window now")
  @APIResponse(responseCode = "200", description = "The window that is now open")
  @RolesAllowed({"qits:admin", "qits:system"})
  public BumpWindowDto openWindow() {
    Instant now = Instant.now();
    dispatcher.open(now);
    return inventory
        .bumpWindow(now)
        .orElseThrow(() -> new IllegalStateException("the window was opened and is not there"));
  }

  /**
   * Closes one. 204 whether or not there was one to close — the caller asked for a state, not for a
   * transition, and every one of the dispatcher's own closing conditions is idempotent too.
   */
  @DELETE
  @jakarta.ws.rs.Path("/window")
  @Operation(summary = "Close the bump dispatch window")
  @APIResponse(responseCode = "204", description = "There is no window now")
  @RolesAllowed({"qits:admin", "qits:system"})
  public void closeWindow() {
    dispatcher.close("it was closed by hand");
  }

  @GET
  @Operation(summary = "The newest bumps, of one repository or of all of them")
  @APIResponse(responseCode = "200", description = "The bumps")
  @RolesAllowed({"qits:admin", "qits:system"})
  public List<BumpDto> bumps(
      @QueryParam("repository") String repository,
      @QueryParam("limit") @DefaultValue("" + DEFAULT_LIMIT) int limit) {
    return inventory.bumps(repository, Math.clamp(limit, 1, MAX_LIMIT));
  }

  /**
   * One bump: the row, the changes it sent, and the ci run it is following.
   *
   * <p>An id that is not a uuid is a 404 like any other unknown bump — a malformed id and an absent
   * one are the same question from the caller's side.
   */
  @GET
  @jakarta.ws.rs.Path("/{id}")
  @Operation(summary = "One bump with the changes it sent")
  @APIResponse(responseCode = "200", description = "The bump")
  @APIResponse(responseCode = "404", description = "No such bump")
  @RolesAllowed({"qits:admin", "qits:system"})
  public BumpDto bump(@PathParam("id") String id) {
    UUID bumpId;
    try {
      bumpId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new NoSuchBumpException(id);
    }
    return inventory.bump(bumpId);
  }
}
