package eu.wohlben.qits.domain.telemetry.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.telemetry.dto.MetricPoint;
import eu.wohlben.qits.domain.telemetry.dto.SpanEvent;
import eu.wohlben.qits.domain.telemetry.dto.StoredLog;
import eu.wohlben.qits.domain.telemetry.dto.StoredSpan;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Plain-JUnit test of the store's bounding, indexing and bucket isolation — no Quarkus needed. */
class TelemetryStoreTest {

  private TelemetryStore store;

  @BeforeEach
  void setUp() {
    store = new TelemetryStore();
  }

  private static Map<String, String> qitsAttributes(String repoId, String worktreeId) {
    return Map.of(
        "service.name", "svc", "qits.repository.id", repoId, "qits.worktree.id", worktreeId);
  }

  private static StoredSpan span(
      String traceId, String name, Map<String, String> resourceAttributes, long receivedAt) {
    return new StoredSpan(
        traceId,
        "span-" + name,
        "",
        "svc",
        "scope",
        name,
        "SERVER",
        1_000_000_000L,
        1_250_000_000L,
        "UNSET",
        "",
        Map.of(),
        List.of(),
        resourceAttributes,
        receivedAt);
  }

  private static StoredLog log(
      String body, Map<String, String> resourceAttributes, long receivedAt) {
    return new StoredLog(
        1_000_000_000L, 9, "INFO", body, "", "", "svc", Map.of(), resourceAttributes, receivedAt);
  }

  private static MetricPoint metric(
      String name, double value, Map<String, String> attributes, Map<String, String> resource) {
    return new MetricPoint(
        name, "", "By", "GAUGE", value, 1_000_000_000L, attributes, "svc", resource, 1L);
  }

  @Test
  void bucketsAreIsolatedByWorktree() {
    store.addSpans(List.of(span("t1", "a", qitsAttributes("repoA", "wt1"), 1)));
    store.addSpans(List.of(span("t2", "b", qitsAttributes("repoB", "wt2"), 2)));

    assertEquals(1, store.spans("repoA", "wt1").size());
    assertEquals("a", store.spans("repoA", "wt1").getFirst().name());
    assertEquals(1, store.spans("repoB", "wt2").size());
    assertTrue(store.spans("repoA", "wt2").isEmpty());
  }

  @Test
  void spanCapEvictsOldestAndPrunesTraceIndex() {
    store.maxSpansPerWorktree = 3;
    Map<String, String> attrs = qitsAttributes("repo", "wt");
    for (int i = 1; i <= 5; i++) {
      store.addSpans(List.of(span("trace-" + i, "span-" + i, attrs, i)));
    }

    List<StoredSpan> remaining = store.spans("repo", "wt");
    assertEquals(3, remaining.size());
    assertEquals("span-3", remaining.getFirst().name());
    assertEquals("span-5", remaining.getLast().name());
    assertTrue(store.trace("repo", "wt", "trace-1").isEmpty(), "evicted span left in trace index");
    assertEquals(1, store.trace("repo", "wt", "trace-4").size());
  }

  @Test
  void byteAccountingReturnsToZeroWhenEverythingEvicts() {
    store.maxSpansPerWorktree = 1;
    store.maxLogsPerWorktree = 1;
    Map<String, String> attrs = qitsAttributes("repo", "wt");
    for (int i = 0; i < 4; i++) {
      store.addSpans(List.of(span("t", "s" + i, attrs, i)));
      store.addLogs(List.of(log("l" + i, attrs, i)));
    }
    long expected =
        TelemetrySizeEstimator.bytesOf(store.spans("repo", "wt").getFirst())
            + TelemetrySizeEstimator.bytesOf(store.logs("repo", "wt").getFirst());
    assertEquals(expected, store.totalBytes());

    store.clear();
    assertEquals(0, store.totalBytes());
    assertTrue(store.spans("repo", "wt").isEmpty());
  }

  @Test
  void globalCeilingEvictsFromFattestBucketFirst() {
    Map<String, String> chatty = qitsAttributes("repo", "chatty");
    Map<String, String> quiet = qitsAttributes("repo", "quiet");
    store.addLogs(List.of(log("quiet log", quiet, 1)));
    long quietBytes = store.totalBytes();

    // A ceiling that fits the quiet log plus roughly two chatty logs.
    store.maxTotalBytes =
        quietBytes + 3 * TelemetrySizeEstimator.bytesOf(log("chatty 0", chatty, 0));
    for (int i = 0; i < 20; i++) {
      store.addLogs(List.of(log("chatty " + i, chatty, 10 + i)));
    }

    assertEquals(1, store.logs("repo", "quiet").size(), "quiet worktree lost telemetry");
    assertTrue(store.totalBytes() <= store.maxTotalBytes);
    List<StoredLog> chattyLogs = store.logs("repo", "chatty");
    assertTrue(chattyLogs.size() < 20, "chatty bucket was not evicted");
    assertEquals("chatty 19", chattyLogs.getLast().body(), "newest chatty log must survive");
  }

  @Test
  void globalCeilingEvictsOldestAcrossSpansAndLogs() {
    Map<String, String> attrs = qitsAttributes("repo", "wt");
    store.addSpans(List.of(span("t-old", "oldest-span", attrs, 1)));
    store.addLogs(List.of(log("newer log", attrs, 2)));
    store.maxTotalBytes = store.totalBytes(); // exactly full — the next append must evict

    store.addLogs(List.of(log("newest log", attrs, 3)));

    assertTrue(store.spans("repo", "wt").isEmpty(), "oldest record was a span; it must go first");
    assertEquals(2, store.logs("repo", "wt").size());
  }

  @Test
  void metricSeriesReplaceInPlaceAndNewSeriesAreCappedButUpdatesStillLand() {
    store.maxMetricSeriesPerWorktree = 2;
    Map<String, String> attrs = qitsAttributes("repo", "wt");
    store.addMetrics(List.of(metric("m1", 1.0, Map.of("k", "a"), attrs)));
    store.addMetrics(List.of(metric("m1", 2.0, Map.of("k", "a"), attrs)));
    store.addMetrics(List.of(metric("m2", 5.0, Map.of(), attrs)));
    store.addMetrics(List.of(metric("m3", 9.0, Map.of(), attrs))); // over the cap: dropped
    store.addMetrics(List.of(metric("m2", 6.0, Map.of(), attrs))); // update of existing: lands

    List<MetricPoint> metrics = store.metrics("repo", "wt");
    assertEquals(2, metrics.size());
    assertEquals(
        2.0, metrics.stream().filter(m -> m.name().equals("m1")).findFirst().orElseThrow().value());
    assertEquals(
        6.0, metrics.stream().filter(m -> m.name().equals("m2")).findFirst().orElseThrow().value());
  }

  @Test
  void unattributedTelemetryIsQuarantinedNotVisibleToAnyWorktree() {
    store.addSpans(List.of(span("t", "unscoped", Map.of("service.name", "svc"), 1)));

    assertTrue(store.spans("repo", "wt").isEmpty());
    assertTrue(store.totalBytes() > 0, "unscoped telemetry must still be retained (and bounded)");
  }

  @Test
  void exceptionEventAndErrorHelpersWork() {
    StoredSpan error =
        new StoredSpan(
            "t",
            "s",
            "",
            "svc",
            "scope",
            "GET /boom",
            "SERVER",
            0,
            2_000_000L,
            "ERROR",
            "boom",
            Map.of(),
            List.of(new SpanEvent("exception", 1L, Map.of("exception.type", "X"))),
            Map.of(),
            1L);
    assertTrue(error.isError());
    assertTrue(error.hasExceptionEvent());
    assertEquals(2, error.durationMs());
    assertTrue(new StoredLog(1, 17, "ERROR", "b", "", "", "s", Map.of(), Map.of(), 1).isError());
  }
}
