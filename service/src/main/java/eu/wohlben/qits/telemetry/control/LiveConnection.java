package eu.wohlben.qits.telemetry.control;

import java.util.ArrayDeque;
import org.jboss.logging.Logger;

/**
 * One live-stream connection: its filter and its bounded frame queue.
 *
 * <p>One frame is on the wire at a time. The rest wait in the queue, which holds at most {@code
 * capacity} record frames. A record frame that finds the queue full is dropped and counted. When the
 * queue next runs empty, the connection sends one {@code {"dropped": N}} notice and the count starts
 * again. So a slow reader costs its own frames and nothing else: the dispatcher never waits for it.
 *
 * <p>Notices ({@code {"error": …}}) answer something the client sent, so they skip the capacity
 * check and are never dropped.
 */
final class LiveConnection {

  private static final Logger LOG = Logger.getLogger(LiveConnection.class);

  private final TelemetryStreamSink sink;
  private final int capacity;

  /** Replaced whole by each subscribe frame, and read by the dispatcher without a lock. */
  private volatile TelemetryFilter filter = TelemetryFilter.NOTHING;

  // All guarded by this.
  private final ArrayDeque<String> queue = new ArrayDeque<>();
  private long dropped;
  private boolean sending;
  private boolean closed;

  LiveConnection(TelemetryStreamSink sink, int capacity) {
    this.sink = sink;
    this.capacity = Math.max(1, capacity);
  }

  String id() {
    return sink.id();
  }

  TelemetryFilter filter() {
    return filter;
  }

  void filter(TelemetryFilter filter) {
    this.filter = filter;
  }

  /** Queue one record frame, or drop and count it when the queue is full. */
  void offer(String frame) {
    synchronized (this) {
      if (closed) {
        return;
      }
      if (queue.size() >= capacity) {
        dropped++;
        return;
      }
      queue.addLast(frame);
      if (sending) {
        return;
      }
      sending = true;
    }
    pump();
  }

  /** Queue a notice. Never dropped. */
  void notice(String frame) {
    synchronized (this) {
      if (closed) {
        return;
      }
      queue.addLast(frame);
      if (sending) {
        return;
      }
      sending = true;
    }
    pump();
  }

  /** Count records that matched but never reached the queue (the dispatcher was full). */
  void dropped(long count) {
    synchronized (this) {
      if (closed || count <= 0) {
        return;
      }
      dropped += count;
      if (sending) {
        return;
      }
      sending = true;
    }
    pump();
  }

  /** Stop sending. Idempotent. */
  void close() {
    synchronized (this) {
      closed = true;
      queue.clear();
      dropped = 0;
    }
  }

  /** Send the next frame, or the dropped notice when the queue is empty, or stop. */
  private void pump() {
    String next;
    synchronized (this) {
      next = closed ? null : queue.pollFirst();
      if (next == null && !closed && dropped > 0) {
        next = "{\"dropped\":" + dropped + "}";
        dropped = 0;
      }
      if (next == null) {
        sending = false;
        return;
      }
    }
    try {
      sink.send(next, this::pump);
    } catch (RuntimeException refused) {
      LOG.debugf("Live stream connection %s refused a frame: %s", sink.id(), refused.getMessage());
      synchronized (this) {
        sending = false;
      }
    }
  }
}
