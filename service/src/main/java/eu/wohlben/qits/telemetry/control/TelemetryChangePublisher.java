package eu.wohlben.qits.telemetry.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The one-liner {@link TelemetryStore} calls to announce that a workspace's telemetry changed.
 * Wraps CDI {@link Event#fireAsync} so firing never blocks or fails the ingest request thread —
 * the store fires while callers are mid-batch, so the emit must return immediately and hand off to
 * the async observer thread.
 *
 * <p>The extracted counterpart of the monorepo's {@code WorkspaceChangePublisher}, narrowed to the
 * single topic this context ever fired (see {@link TelemetryChanged}). It is a class rather than an
 * interface for the same reason the original was: the plain-JUnit store test subclasses it and
 * overrides {@link #fire} to record instead of routing through CDI.
 */
@ApplicationScoped
public class TelemetryChangePublisher {

  @Inject Event<TelemetryChanged> event;

  public void fire(String repoId, String workspaceId) {
    event.fireAsync(new TelemetryChanged(repoId, workspaceId));
  }
}
