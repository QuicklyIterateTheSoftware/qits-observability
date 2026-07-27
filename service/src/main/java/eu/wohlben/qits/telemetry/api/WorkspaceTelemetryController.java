package eu.wohlben.qits.telemetry.api;

import eu.wohlben.qits.telemetry.control.TelemetryQueryService;
import eu.wohlben.qits.telemetry.dto.TelemetryErrorGroupDto;
import eu.wohlben.qits.telemetry.dto.TelemetryLogDto;
import eu.wohlben.qits.telemetry.dto.TelemetryMetricDto;
import eu.wohlben.qits.telemetry.dto.TelemetrySpanDto;
import eu.wohlben.qits.telemetry.dto.TelemetryTraceDto;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * The REST twins of the telemetry MCP tools, for the UI's workspace telemetry tab. Read-only JSON
 * over the same {@link TelemetryQueryService}, so humans and agents see identical answers.
 *
 * <p>The repository and the workspace are <em>scope</em>, not containment: this context owns
 * neither, and buckets telemetry by the string ids an exporter stamped into its resource
 * attributes. So they arrive as {@code ?repositoryId=&workspaceId=} filters rather than as path
 * segments of another context's aggregate — {@code traceId} stays in the path because it is the
 * identity of the thing being fetched.
 *
 * <p>Nothing here is an authorization boundary: an unknown or foreign scope selects a bucket that
 * is simply empty. The scoping ports ({@code RepositoryScopeGuard} / {@code WorkspaceLookup}) guard
 * the MCP surface, where the scope comes from the agent's connection rather than from the caller.
 */
@Path("/telemetry")
@Produces(MediaType.APPLICATION_JSON)
public class WorkspaceTelemetryController {

  @Inject TelemetryQueryService queryService;

  public static record ListTelemetryErrorsRequest() {
    public record Response(List<TelemetryErrorGroupDto> groups) {}
  }

  @GET
  @Path("/errors")
  public ListTelemetryErrorsRequest.Response errors(
      @QueryParam("repositoryId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @QueryParam("sinceMinutes") Integer sinceMinutes) {
    return new ListTelemetryErrorsRequest.Response(
        queryService.errors(repoId, workspaceId, sinceMinutes));
  }

  public static record GetTelemetryTraceRequest() {
    public record Response(TelemetryTraceDto trace) {}
  }

  @GET
  @Path("/traces/{traceId}")
  public GetTelemetryTraceRequest.Response trace(
      @QueryParam("repositoryId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @PathParam("traceId") String traceId) {
    return new GetTelemetryTraceRequest.Response(queryService.trace(repoId, workspaceId, traceId));
  }

  public static record ListSlowSpansRequest() {
    public record Response(List<TelemetrySpanDto> spans) {}
  }

  @GET
  @Path("/slow-spans")
  public ListSlowSpansRequest.Response slowSpans(
      @QueryParam("repositoryId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @QueryParam("thresholdMs") @DefaultValue("500") long thresholdMs,
      @QueryParam("sinceMinutes") Integer sinceMinutes,
      @QueryParam("sort") @DefaultValue("duration") String sort) {
    TelemetryQueryService.SpanSort spanSort =
        "recent".equalsIgnoreCase(sort)
            ? TelemetryQueryService.SpanSort.RECENT
            : TelemetryQueryService.SpanSort.DURATION;
    return new ListSlowSpansRequest.Response(
        queryService.slowSpans(repoId, workspaceId, thresholdMs, sinceMinutes, spanSort));
  }

  public static record SearchTelemetryLogsRequest() {
    public record Response(List<TelemetryLogDto> logs) {}
  }

  @GET
  @Path("/logs")
  public SearchTelemetryLogsRequest.Response logs(
      @QueryParam("repositoryId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @QueryParam("query") String query,
      @QueryParam("service") String service,
      @QueryParam("sinceMinutes") Integer sinceMinutes) {
    return new SearchTelemetryLogsRequest.Response(
        queryService.searchLogs(repoId, workspaceId, query, sinceMinutes, service));
  }

  public static record ListTelemetryMetricsRequest() {
    public record Response(List<TelemetryMetricDto> metrics) {}
  }

  @GET
  @Path("/metrics")
  public ListTelemetryMetricsRequest.Response metrics(
      @QueryParam("repositoryId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @QueryParam("name") String name) {
    return new ListTelemetryMetricsRequest.Response(
        queryService.metrics(repoId, workspaceId, name));
  }
}
