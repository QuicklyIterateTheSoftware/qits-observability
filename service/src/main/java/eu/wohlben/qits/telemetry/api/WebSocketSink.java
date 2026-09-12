package eu.wohlben.qits.telemetry.api;

import eu.wohlben.qits.telemetry.control.TelemetryStreamSink;
import io.quarkus.websockets.next.WebSocketConnection;
import org.jboss.logging.Logger;

/**
 * A {@link TelemetryStreamSink} over a websockets-next connection.
 *
 * <p>{@code sendText(…)} is a {@code Uni}, subscribed to with both outcomes calling {@code done}.
 * Never {@code sendTextAndAwait}: the caller is the feed's dispatcher thread, and a wait here would
 * let one slow reader stop every other one.
 */
final class WebSocketSink implements TelemetryStreamSink {

  private static final Logger LOG = Logger.getLogger(WebSocketSink.class);

  private final WebSocketConnection connection;

  WebSocketSink(WebSocketConnection connection) {
    this.connection = connection;
  }

  @Override
  public String id() {
    return connection.id();
  }

  @Override
  public void send(String frame, Runnable done) {
    if (!connection.isOpen()) {
      done.run();
      return;
    }
    connection
        .sendText(frame)
        .subscribe()
        .with(
            sent -> done.run(),
            failure -> {
              LOG.debugf(
                  "Dropped a live stream frame for connection %s: %s",
                  connection.id(), failure.getMessage());
              done.run();
            });
  }
}
