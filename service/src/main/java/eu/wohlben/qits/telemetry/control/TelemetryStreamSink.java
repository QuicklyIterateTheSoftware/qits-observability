package eu.wohlben.qits.telemetry.control;

/**
 * One open reader of the live stream, as {@link TelemetryLiveFeed} sees it. The transport is not
 * its business: {@code api/WebSocketSink} is the one implementation, and the tests use an in-memory
 * one.
 */
public interface TelemetryStreamSink {

  /** The key this reader is held under. Stable for the life of the connection. */
  String id();

  /**
   * Write one text frame. Must not block and must not throw. Call {@code done} exactly once when
   * the write finished, whether it worked or not: the next frame waits for it.
   */
  void send(String frame, Runnable done);
}
