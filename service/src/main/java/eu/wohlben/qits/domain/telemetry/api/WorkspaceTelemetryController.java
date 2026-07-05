package eu.wohlben.qits.domain.telemetry.api;

import eu.wohlben.qits.domain.telemetry.control.TelemetryQueryService;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryErrorGroupDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryLogDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryMetricDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetrySpanDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryTraceDto;
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
 */
@Path("/repositories/{repoId}/workspaces/{workspaceId}/telemetry")
@Produces(MediaType.APPLICATION_JSON)
public class WorkspaceTelemetryController {

  @Inject TelemetryQueryService queryService;

  public static record ListTelemetryErrorsRequest() {
    public record Response(List<TelemetryErrorGroupDto> groups) {}
  }

  @GET
  @Path("/errors")
  public ListTelemetryErrorsRequest.Response errors(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
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
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @PathParam("traceId") String traceId) {
    return new GetTelemetryTraceRequest.Response(queryService.trace(repoId, workspaceId, traceId));
  }

  public static record ListSlowSpansRequest() {
    public record Response(List<TelemetrySpanDto> spans) {}
  }

  @GET
  @Path("/slow-spans")
  public ListSlowSpansRequest.Response slowSpans(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @QueryParam("thresholdMs") @DefaultValue("500") long thresholdMs,
      @QueryParam("sinceMinutes") Integer sinceMinutes) {
    return new ListSlowSpansRequest.Response(
        queryService.slowSpans(repoId, workspaceId, thresholdMs, sinceMinutes));
  }

  public static record SearchTelemetryLogsRequest() {
    public record Response(List<TelemetryLogDto> logs) {}
  }

  @GET
  @Path("/logs")
  public SearchTelemetryLogsRequest.Response logs(
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
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
      @PathParam("repoId") String repoId,
      @PathParam("workspaceId") String workspaceId,
      @QueryParam("name") String name) {
    return new ListTelemetryMetricsRequest.Response(
        queryService.metrics(repoId, workspaceId, name));
  }
}
