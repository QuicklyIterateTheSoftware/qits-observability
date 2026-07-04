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
 * The REST twins of the telemetry MCP tools, for the UI's worktree telemetry tab. Read-only JSON
 * over the same {@link TelemetryQueryService}, so humans and agents see identical answers.
 */
@Path("/repositories/{repoId}/worktrees/{worktreeId}/telemetry")
@Produces(MediaType.APPLICATION_JSON)
public class WorktreeTelemetryController {

  @Inject TelemetryQueryService queryService;

  public static record ListTelemetryErrorsRequest() {
    public record Response(List<TelemetryErrorGroupDto> groups) {}
  }

  @GET
  @Path("/errors")
  public ListTelemetryErrorsRequest.Response errors(
      @PathParam("repoId") String repoId,
      @PathParam("worktreeId") String worktreeId,
      @QueryParam("sinceMinutes") Integer sinceMinutes) {
    return new ListTelemetryErrorsRequest.Response(
        queryService.errors(repoId, worktreeId, sinceMinutes));
  }

  public static record GetTelemetryTraceRequest() {
    public record Response(TelemetryTraceDto trace) {}
  }

  @GET
  @Path("/traces/{traceId}")
  public GetTelemetryTraceRequest.Response trace(
      @PathParam("repoId") String repoId,
      @PathParam("worktreeId") String worktreeId,
      @PathParam("traceId") String traceId) {
    return new GetTelemetryTraceRequest.Response(queryService.trace(repoId, worktreeId, traceId));
  }

  public static record ListSlowSpansRequest() {
    public record Response(List<TelemetrySpanDto> spans) {}
  }

  @GET
  @Path("/slow-spans")
  public ListSlowSpansRequest.Response slowSpans(
      @PathParam("repoId") String repoId,
      @PathParam("worktreeId") String worktreeId,
      @QueryParam("thresholdMs") @DefaultValue("500") long thresholdMs,
      @QueryParam("sinceMinutes") Integer sinceMinutes) {
    return new ListSlowSpansRequest.Response(
        queryService.slowSpans(repoId, worktreeId, thresholdMs, sinceMinutes));
  }

  public static record SearchTelemetryLogsRequest() {
    public record Response(List<TelemetryLogDto> logs) {}
  }

  @GET
  @Path("/logs")
  public SearchTelemetryLogsRequest.Response logs(
      @PathParam("repoId") String repoId,
      @PathParam("worktreeId") String worktreeId,
      @QueryParam("query") String query,
      @QueryParam("service") String service,
      @QueryParam("sinceMinutes") Integer sinceMinutes) {
    return new SearchTelemetryLogsRequest.Response(
        queryService.searchLogs(repoId, worktreeId, query, sinceMinutes, service));
  }

  public static record ListTelemetryMetricsRequest() {
    public record Response(List<TelemetryMetricDto> metrics) {}
  }

  @GET
  @Path("/metrics")
  public ListTelemetryMetricsRequest.Response metrics(
      @PathParam("repoId") String repoId,
      @PathParam("worktreeId") String worktreeId,
      @QueryParam("name") String name) {
    return new ListTelemetryMetricsRequest.Response(queryService.metrics(repoId, worktreeId, name));
  }
}
