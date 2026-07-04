package eu.wohlben.qits.domain.telemetry.mcp;

import eu.wohlben.qits.domain.repository.mcp.ProjectScope;
import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Exposes the telemetry tools only to sessions scoped all the way down to a worktree (repository
 * <em>and</em> worktree narrowing present): telemetry is bucketed per worktree, so a broader
 * session has nothing it may query. Mirrors {@code RepositoryActionToolFilter}, fails closed.
 */
@ApplicationScoped
public class TelemetryToolFilter implements ToolFilter {

  /** The telemetry tools of the "repository" MCP server (see {@link TelemetryMcpTools}). */
  private static final Set<String> TELEMETRY_TOOLS =
      Set.of(
          "telemetryErrors",
          "telemetryTrace",
          "telemetrySlowSpans",
          "telemetrySearchLogs",
          "telemetryMetrics");

  @Inject ProjectScope projectScope;

  @Inject WorktreeScope worktreeScope;

  @Override
  public boolean test(ToolInfo tool, McpConnection connection) {
    if (!TELEMETRY_TOOLS.contains(tool.name())) {
      return true;
    }
    // Fail closed: if the request scope can't be read, hide the telemetry tools rather than
    // letting the listing error.
    try {
      return projectScope.repositoryId().isPresent() && worktreeScope.hasWorktree();
    } catch (RuntimeException e) {
      return false;
    }
  }
}
