package eu.wohlben.qits.maintenance.api;

import eu.wohlben.qits.maintenance.error.MaintenanceException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps the domain's framework-free {@link MaintenanceException}s (each carrying a status code) to
 * HTTP responses — kept here in {@code service} because the domain module carries no JAX-RS.
 *
 * <p>The envelope is the platform's: {@code {"message": "..."}}, one key, the sentence the domain
 * threw. A 409 names the bump that holds the lock, so a caller told "already active" can go and
 * read it rather than guess.
 */
@Provider
public class MaintenanceExceptionMapper implements ExceptionMapper<MaintenanceException> {

  @Override
  public Response toResponse(MaintenanceException exception) {
    int status = exception.statusCode();
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = Response.Status.fromStatusCode(status).getReasonPhrase();
    }
    return Response.status(status)
        .entity(Map.of("message", message))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
