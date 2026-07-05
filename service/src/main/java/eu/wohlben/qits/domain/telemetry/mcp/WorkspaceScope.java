package eu.wohlben.qits.domain.telemetry.mcp;

import eu.wohlben.qits.domain.error.BadRequestException;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Resolves the workspace a "repository" MCP session is optionally scoped to — the third scope
 * dimension after project and repository, added for the telemetry tools: a workspace-launched agent
 * session carries {@code ?workspaceId=} in its MCP URL (see {@code AgentLaunchService}) and may
 * only query that workspace's telemetry. Taken from the request, never a tool argument, so a
 * session can't widen its own scope.
 *
 * <p>Mirrors {@link eu.wohlben.qits.domain.featureflow.mcp.RepositoryScope}; the scope is optional
 * — {@link #workspaceId()} returns empty when absent, which the {@link TelemetryToolFilter} uses to
 * hide the telemetry tools from sessions that aren't pinned to a workspace.
 */
@RequestScoped
public class WorkspaceScope {

  /** Header carrying the workspace id the connection is scoped to. */
  public static final String WORKSPACE_HEADER = "X-QITS-Workspace";

  /** Query-parameter fallback when a client cannot set a custom header. */
  public static final String WORKSPACE_QUERY_PARAM = "workspaceId";

  @Inject HttpServerRequest request;

  /** The scoped workspace id, or empty when the connection didn't supply one. */
  public Optional<String> workspaceId() {
    String id = request.getHeader(WORKSPACE_HEADER);
    if (id == null || id.isBlank()) {
      id = request.getParam(WORKSPACE_QUERY_PARAM);
    }
    return (id == null || id.isBlank()) ? Optional.empty() : Optional.of(id.trim());
  }

  /** Whether this session is scoped to a workspace. */
  public boolean hasWorkspace() {
    return workspaceId().isPresent();
  }

  /** The scoped workspace id, or throws when the connection didn't supply one. */
  public String requireWorkspaceId() {
    return workspaceId()
        .orElseThrow(
            () ->
                new BadRequestException(
                    "This MCP session is not scoped to a workspace. Connect with the '"
                        + WORKSPACE_HEADER
                        + "' header (or '?"
                        + WORKSPACE_QUERY_PARAM
                        + "=' query parameter) set to a workspace id."));
  }
}
