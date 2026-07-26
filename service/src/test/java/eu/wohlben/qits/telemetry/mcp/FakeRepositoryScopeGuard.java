package eu.wohlben.qits.telemetry.mcp;

import eu.wohlben.qits.telemetry.error.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Test double for the {@link RepositoryScopeGuard} port: the projects context's "is this repository
 * inside the session's project?" check, without a projects database.
 *
 * <p>In the monorepo this test drove the real guard by POSTing {@code /api/projects} and {@code
 * /api/projects/{id}/repositories} through {@code ProjectController} and cloning the
 * {@code testing-repo.git} fixture. Neither exists here — both belong to qits-projects — so the
 * port is faked instead. The assertions the suite actually makes (scope isolation, per-workspace
 * bucketing, tool visibility) are unchanged; only the setup is.
 */
@ApplicationScoped
public class FakeRepositoryScopeGuard implements RepositoryScopeGuard {

  private final Set<String> inProject = new CopyOnWriteArraySet<>();

  /** Registers a repository as belonging to the session's project. */
  public void allow(String repoId) {
    inProject.add(repoId);
  }

  public void reset() {
    inProject.clear();
  }

  @Override
  public void requireRepoInProject(String repoId) {
    if (!inProject.contains(repoId)) {
      throw new NotFoundException("Repository not found in this project: " + repoId);
    }
  }
}
