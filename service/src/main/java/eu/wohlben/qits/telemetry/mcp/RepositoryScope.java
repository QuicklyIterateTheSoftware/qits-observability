package eu.wohlben.qits.telemetry.mcp;

import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.Optional;

/**
 * Resolves the repository an MCP session is narrowed to — the second scope dimension, between
 * project and workspace. Taken from the connection's HTTP request rather than from a tool argument,
 * so the model has no parameter it could point at another repository and cannot widen its own
 * scope.
 *
 * <p>The extracted counterpart of the monorepo's {@code
 * eu.wohlben.qits.domain.repository.mcp.ProjectScope}, carrying its {@code X-QITS-Repository}
 * header and {@code ?repositoryId=} fallback verbatim (migration-plan.md §5, "duplicate now,
 * library later" — the header contract is the shared thing, not the class). Deliberately only the
 * repository half: the project half ({@code X-QITS-Project} / {@code requireProjectId}) is used
 * here solely by {@link RepositoryScopeGuard}, which is a port the projects context implements, and
 * duplicating it would have been dead code.
 *
 * <p>Sibling of {@link WorkspaceScope}, which does the same for the workspace narrowing.
 */
@RequestScoped
public class RepositoryScope {

  /** Header narrowing the session to a single repository. */
  public static final String REPOSITORY_HEADER = "X-QITS-Repository";

  /** Query-parameter fallback when a client cannot set a custom header. */
  public static final String REPOSITORY_QUERY_PARAM = "repositoryId";

  @Inject HttpServerRequest request;

  /**
   * The single repository this session is narrowed to, or empty when it isn't. When present, tools
   * may only touch this one repository.
   */
  public Optional<String> repositoryId() {
    String repositoryId = request.getHeader(REPOSITORY_HEADER);
    if (repositoryId == null || repositoryId.isBlank()) {
      repositoryId = request.getParam(REPOSITORY_QUERY_PARAM);
    }
    if (repositoryId == null || repositoryId.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(repositoryId.trim());
  }
}
