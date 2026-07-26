package eu.wohlben.qits.telemetry.mcp;

import io.quarkiverse.mcp.server.McpConnection;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Exposes the telemetry tools only to sessions scoped all the way down to a workspace (repository
 * <em>and</em> workspace narrowing present): telemetry is bucketed per workspace, so a broader
 * session has nothing it may query. Fails closed.
 *
 * <p>Also fails closed when the application implements no {@link RepositoryScopeGuard} /
 * {@link WorkspaceLookup} — {@link TelemetryMcpTools} would reject every call, so listing the tools
 * would only advertise a dead end.
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

  @Inject RepositoryScope repositoryScope;

  @Inject WorkspaceScope workspaceScope;

  @Inject Instance<RepositoryScopeGuard> scopeGuard;

  @Inject Instance<WorkspaceLookup> workspaceLookup;

  @Override
  public boolean test(ToolInfo tool, McpConnection connection) {
    if (!TELEMETRY_TOOLS.contains(tool.name())) {
      return true;
    }
    // Fail closed: if the request scope can't be read, hide the telemetry tools rather than
    // letting the listing error.
    try {
      return scopeGuard.isResolvable()
          && workspaceLookup.isResolvable()
          && repositoryScope.repositoryId().isPresent()
          && workspaceScope.hasWorkspace();
    } catch (RuntimeException e) {
      return false;
    }
  }
}
