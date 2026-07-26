package eu.wohlben.qits.telemetry.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Test double for the {@link WorkspaceLookup} port: the workspaces context's "is this an active
 * workspace of that repository?" check, without a workspaces database.
 *
 * <p>Replaces the monorepo test's {@code POST /api/repositories/{repoId}/workspaces}, which really
 * created a branch, a container and a row — none of which this context can or should do.
 */
@ApplicationScoped
public class FakeWorkspaceLookup implements WorkspaceLookup {

  private final Set<String> active = new CopyOnWriteArraySet<>();

  /** Registers an active workspace under a repository. */
  public void register(String repoId, String workspaceId) {
    active.add(repoId + "/" + workspaceId);
  }

  public void reset() {
    active.clear();
  }

  @Override
  public boolean isActiveWorkspace(String repoId, String workspaceId) {
    return active.contains(repoId + "/" + workspaceId);
  }
}
