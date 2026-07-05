package eu.wohlben.qits.domain.telemetry.mcp;

import eu.wohlben.qits.domain.error.NotFoundException;
import eu.wohlben.qits.domain.repository.mcp.ProjectScope;
import eu.wohlben.qits.domain.repository.mcp.ProjectScopeGuard;
import eu.wohlben.qits.domain.repository.persistence.WorkspaceRepository;
import eu.wohlben.qits.domain.telemetry.control.TelemetryQueryService;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryErrorGroupDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryLogDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryMetricDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetrySpanDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryTraceDto;
import io.quarkiverse.mcp.server.McpServer;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.WrapBusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 * The telemetry query tools of the "repository" MCP server: structured access to the spans, logs
 * and metrics that instrumented processes (daemons launched with the {@code otel} toggle) exported
 * into qits' in-process OTLP receiver. This beats log scraping because exceptions arrive as
 * structured span events with stack traces, correlated by trace id.
 *
 * <p>Unlike the other repository tools these take no {@code repoId}: identity comes entirely from
 * the connection — the session's repository narrowing ({@code X-QITS-Repository}) and workspace
 * narrowing ({@code X-QITS-Workspace} / {@code ?workspaceId=}), both stamped into the MCP URL at
 * agent launch. {@link TelemetryToolFilter} hides these tools from any session not scoped that far
 * down, and every call re-validates the scope, so an agent can only ever see its own workspace's
 * telemetry.
 */
@ApplicationScoped
@WrapBusinessError
public class TelemetryMcpTools {

  @Inject ProjectScope projectScope;

  @Inject ProjectScopeGuard scopeGuard;

  @Inject WorkspaceScope workspaceScope;

  @Inject WorkspaceRepository workspaceRepository;

  @Inject TelemetryQueryService queryService;

  @McpServer("repository")
  @Tool(
      description =
          "Recent errors from this workspace's telemetry, grouped by trace: error-status spans,"
              + " exception events (with structured stack traces) and ERROR logs. Start here when"
              + " investigating a failure; follow a traceId with telemetryTrace.")
  @Transactional
  public List<TelemetryErrorGroupDto> telemetryErrors(
      @ToolArg(
              required = false,
              description = "only errors received in the last N minutes (default: all buffered)")
          Integer sinceMinutes) {
    Scope scope = requireScope();
    return queryService.errors(scope.repoId(), scope.workspaceId(), sinceMinutes);
  }

  @McpServer("repository")
  @Tool(
      description =
          "Everything buffered for one trace: its spans (flat list, parent-annotated) plus the log"
              + " records correlated to it by trace id.")
  @Transactional
  public TelemetryTraceDto telemetryTrace(
      @ToolArg(description = "hex trace id (see telemetryErrors)") String traceId) {
    Scope scope = requireScope();
    return queryService.trace(scope.repoId(), scope.workspaceId(), traceId);
  }

  @McpServer("repository")
  @Tool(
      description =
          "Spans in this workspace's telemetry that took at least thresholdMs, slowest first.")
  @Transactional
  public List<TelemetrySpanDto> telemetrySlowSpans(
      @ToolArg(description = "minimum span duration in milliseconds") long thresholdMs,
      @ToolArg(
              required = false,
              description = "only spans received in the last N minutes (default: all buffered)")
          Integer sinceMinutes) {
    Scope scope = requireScope();
    return queryService.slowSpans(scope.repoId(), scope.workspaceId(), thresholdMs, sinceMinutes);
  }

  @McpServer("repository")
  @Tool(
      description =
          "Search this workspace's telemetry logs by case-insensitive substring over body and"
              + " severity.")
  @Transactional
  public List<TelemetryLogDto> telemetrySearchLogs(
      @ToolArg(description = "substring to search for") String query,
      @ToolArg(
              required = false,
              description = "only logs received in the last N minutes (default: all buffered)")
          Integer sinceMinutes) {
    Scope scope = requireScope();
    return queryService.searchLogs(scope.repoId(), scope.workspaceId(), query, sinceMinutes, null);
  }

  @McpServer("repository")
  @Tool(
      description =
          "Latest value per metric series from this workspace's telemetry (gauges and counters"
              + " flattened; no time-series math). Optionally narrowed to one metric name.")
  @Transactional
  public List<TelemetryMetricDto> telemetryMetrics(
      @ToolArg(required = false, description = "exact metric name to filter on") String name) {
    Scope scope = requireScope();
    return queryService.metrics(scope.repoId(), scope.workspaceId(), name);
  }

  private record Scope(String repoId, String workspaceId) {}

  /**
   * Resolves and validates the connection's repository + workspace narrowing: the repository must
   * belong to the scoped project, and the workspace must (still) belong to that repository.
   */
  private Scope requireScope() {
    String repoId = projectScope.repositoryId().orElseThrow(this::notScoped);
    scopeGuard.requireRepoInProject(repoId);
    String workspaceId = workspaceScope.requireWorkspaceId();
    workspaceRepository
        .findActiveByRepositoryAndWorkspaceId(repoId, workspaceId)
        .orElseThrow(() -> new NotFoundException("Workspace not found: " + workspaceId));
    return new Scope(repoId, workspaceId);
  }

  private RuntimeException notScoped() {
    return new NotFoundException(
        "This MCP session is not narrowed to a repository; telemetry tools need repository and"
            + " workspace scope.");
  }
}
