package eu.wohlben.qits.telemetry.api;

import eu.wohlben.qits.telemetry.error.DomainException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Maps this context's framework-free {@link DomainException}s (each carrying a status code) to HTTP
 * responses — the same JSON shape as the monorepo's {@code DomainExceptionMapper}.
 *
 * <p>Re-provided rather than inherited: the monorepo's mapper lives in the app-shell package {@code
 * eu.wohlben.qits.api}, which no extracted context receives (migration-plan.md §3.9). Without it a
 * {@link eu.wohlben.qits.telemetry.error.BadRequestException} from the OTLP receiver would surface
 * as a 500 instead of the 400 the suite asserts. Same reason qits-workspaces carries
 * {@code WorkspacesExceptionMapper} and qits-ci {@code CiExceptionMapper}.
 *
 * <p>Scoped to <em>this</em> context's exception type. An application that also runs the monorepo's
 * {@code eu.wohlben.qits.domain.error.DomainException} keeps its own mapper for it; the two coexist
 * because they map unrelated types.
 */
@Provider
public class TelemetryExceptionMapper implements ExceptionMapper<DomainException> {

  @Override
  public Response toResponse(DomainException exception) {
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
