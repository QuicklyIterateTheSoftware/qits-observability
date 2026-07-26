package eu.wohlben.qits.telemetry.mcp;

/**
 * Port: "is this repository inside the project the MCP session is scoped to?".
 *
 * <p>Implemented by the consuming application, because the answer lives in the <em>projects</em>
 * context's database — the monorepo's {@code ProjectScopeGuard} resolves it through {@code
 * ProjectService.getRepositories(scope.requireProjectId())}. This context cannot reach that table
 * and must not grow a JPA relation to it; cross-context references are by string id.
 *
 * <p>Optional. Absent, the telemetry MCP tools <strong>fail closed</strong>: {@link
 * TelemetryToolFilter} does not list them and a direct call is rejected. That is a supported
 * standalone configuration — the OTLP receiver, the store and the REST query surface all work
 * without it — because "no guard" cannot be allowed to mean "no check" on a cross-project isolation
 * boundary.
 */
public interface RepositoryScopeGuard {

  /**
   * Ensures {@code repoId} names a repository inside the project the current session is scoped to,
   * throwing otherwise (also covering a non-existent repository). The monorepo's implementation
   * returns the resolved {@code Repository}; nothing here uses it, so the port is narrowed to the
   * check.
   */
  void requireRepoInProject(String repoId);
}
