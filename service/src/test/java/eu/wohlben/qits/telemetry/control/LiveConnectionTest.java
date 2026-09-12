package eu.wohlben.qits.telemetry.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The per-connection queue, against a sink the test holds: one frame on the wire at a time, at most
 * {@code capacity} waiting, the rest dropped, and one {@code {"dropped": N}} when the queue drains.
 */
class LiveConnectionTest {

  /** A sink whose writes finish only when the test says so. */
  private static final class HeldSink implements TelemetryStreamSink {
    final List<String> sent = new ArrayList<>();
    final ArrayDeque<Runnable> pending = new ArrayDeque<>();

    @Override
    public String id() {
      return "held";
    }

    @Override
    public void send(String frame, Runnable done) {
      sent.add(frame);
      pending.add(done);
    }

    /** Finish writes until none is pending. */
    void drain() {
      while (!pending.isEmpty()) {
        pending.poll().run();
      }
    }
  }

  @Test
  void framesGoOutInOrderOneAtATime() {
    HeldSink sink = new HeldSink();
    LiveConnection connection = new LiveConnection(sink, 8);
    connection.offer("a");
    connection.offer("b");
    connection.offer("c");
    assertEquals(List.of("a"), sink.sent, "only one frame is on the wire");
    sink.drain();
    assertEquals(List.of("a", "b", "c"), sink.sent);
  }

  @Test
  void aFullQueueDropsAndSaysSoOnceWhenItDrains() {
    HeldSink sink = new HeldSink();
    LiveConnection connection = new LiveConnection(sink, 2);
    for (int i = 0; i < 10; i++) {
      connection.offer("r" + i);
    }
    // r0 on the wire, r1 and r2 waiting, r3..r9 dropped.
    sink.drain();
    assertEquals(List.of("r0", "r1", "r2", "{\"dropped\":7}"), sink.sent);

    connection.offer("after");
    sink.drain();
    assertEquals("after", sink.sent.getLast(), "the count starts again after the notice");
    assertEquals(5, sink.sent.size());
  }

  @Test
  void aNoticeIsNeverDropped() {
    HeldSink sink = new HeldSink();
    LiveConnection connection = new LiveConnection(sink, 1);
    connection.offer("r0");
    connection.offer("r1");
    connection.offer("r2");
    connection.notice("{\"error\":\"x\"}");
    sink.drain();
    assertEquals(List.of("r0", "r1", "{\"error\":\"x\"}", "{\"dropped\":1}"), sink.sent);
  }

  @Test
  void recordsTheDispatcherLostAreReportedOnAnIdleConnection() {
    HeldSink sink = new HeldSink();
    LiveConnection connection = new LiveConnection(sink, 4);
    connection.dropped(0);
    assertTrue(sink.sent.isEmpty(), "nothing lost, nothing said");
    connection.dropped(12);
    sink.drain();
    assertEquals(List.of("{\"dropped\":12}"), sink.sent);
  }

  @Test
  void aClosedConnectionSendsNothingMore() {
    HeldSink sink = new HeldSink();
    LiveConnection connection = new LiveConnection(sink, 4);
    connection.offer("r0");
    connection.offer("r1");
    connection.close();
    connection.offer("r2");
    connection.notice("{\"error\":\"x\"}");
    sink.drain();
    assertEquals(List.of("r0"), sink.sent);
  }

  @Test
  void aSinkThatFinishesAtOnceStillDeliversEverything() {
    List<String> sent = new ArrayList<>();
    TelemetryStreamSink immediate =
        new TelemetryStreamSink() {
          @Override
          public String id() {
            return "immediate";
          }

          @Override
          public void send(String frame, Runnable done) {
            sent.add(frame);
            done.run();
          }
        };
    LiveConnection connection = new LiveConnection(immediate, 2);
    for (int i = 0; i < 100; i++) {
      connection.offer("r" + i);
    }
    assertEquals(100, sent.size());
  }
}
