package eu.wohlben.qits.telemetry.mcp;

/**
 * Port: "does this workspace still exist, active, under this repository?".
 *
 * <p>Implemented by the consuming application, because the answer lives in the <em>workspaces</em>
 * context's database — the monorepo's {@code TelemetryMcpTools} injected {@code WorkspaceRepository}
 * and called {@code findActiveByRepositoryAndWorkspaceId}. This context stores telemetry keyed by
 * the {@code qits.repository.id} / {@code qits.workspace.id} resource attributes an exporter
 * stamped; it has no workspace table of its own and must not grow one.
 *
 * <p>Optional, and fails closed for the same reason as {@link RepositoryScopeGuard}: absent, the
 * telemetry MCP tools are hidden and rejected rather than served unvalidated.
 */
public interface WorkspaceLookup {

  /**
   * Whether {@code workspaceId} names an <em>active</em> workspace of {@code repoId}. The monorepo
   * returned the {@code Workspace} entity and threw on empty; nothing here uses the entity, so the
   * port is narrowed to the predicate and the caller keeps the throw.
   */
  boolean isActiveWorkspace(String repoId, String workspaceId);
}
