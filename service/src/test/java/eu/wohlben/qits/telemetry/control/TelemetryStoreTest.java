package eu.wohlben.qits.telemetry.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.dto.MetricPoint;
import eu.wohlben.qits.telemetry.dto.SpanEvent;
import eu.wohlben.qits.telemetry.dto.StoredLog;
import eu.wohlben.qits.telemetry.dto.StoredSource;
import eu.wohlben.qits.telemetry.dto.StoredSpan;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Plain-JUnit test of the store's bounding, indexing and bucket isolation — no Quarkus needed. */
class TelemetryStoreTest {

  private TelemetryStore store;

  @BeforeEach
  void setUp() {
    store = new TelemetryStore();
  }

  private static Map<String, String> qitsAttributes(String repoId, String workspaceId) {
    return Map.of(
        "service.name", "svc", "qits.repository.id", repoId, "qits.workspace.id", workspaceId);
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

  /**
   * A span whose {@code serviceName} agrees with its {@code service.name} resource attribute, the
   * way {@link TelemetryDecoder} produces one. {@link #span} hardcodes "svc" instead, which is
   * fine where the service is irrelevant and wrong where the bucketing keys on it.
   */
  private static StoredSpan serviceSpan(String traceId, String name, String service, long at) {
    Map<String, String> resource = Map.of("service.name", service);
    StoredSpan template = span(traceId, name, resource, at);
    return new StoredSpan(
        template.traceId(),
        template.spanId(),
        template.parentSpanId(),
        service,
        template.scopeName(),
        template.name(),
        template.kind(),
        template.startEpochNanos(),
        template.endEpochNanos(),
        template.status(),
        template.statusMessage(),
        template.attributes(),
        template.events(),
        resource,
        template.receivedAtMillis());
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

  /** Overrides {@link TelemetryChangePublisher#fire} to record instead of routing through CDI. */
  private static final class RecordingPublisher extends TelemetryChangePublisher {
    final List<TelemetryChanged> fired = new CopyOnWriteArrayList<>();

    @Override
    public void fire(String repoId, String workspaceId) {
      fired.add(new TelemetryChanged(repoId, workspaceId));
    }
  }

  @Test
  void appendingScopedTelemetryFiresOneTelemetryHintPerWorkspace() {
    RecordingPublisher publisher = new RecordingPublisher();
    store.changePublisher = publisher;

    store.addSpans(
        List.of(
            span("t1", "a", qitsAttributes("repo", "wt"), 1),
            span("t2", "b", qitsAttributes("repo", "wt"), 2)));
    store.addLogs(List.of(log("hi", qitsAttributes("repo", "wt"), 3)));
    store.addMetrics(List.of(metric("m", 1.0, Map.of(), qitsAttributes("repo", "wt"))));

    // Two spans for one workspace coalesce to one hint; each append method fires once → 3 total.
    // (The monorepo also asserted topic() == Topic.TELEMETRY; TelemetryChanged has no topic
    // field — it IS the telemetry topic — so the event type carries that assertion now.)
    assertEquals(3, publisher.fired.size());
    assertTrue(
        publisher.fired.stream()
            .allMatch(h -> h.repoId().equals("repo") && h.workspaceId().equals("wt")));
  }

  @Test
  void aBatchSpanningTwoWorkspacesFiresAHintForEach() {
    RecordingPublisher publisher = new RecordingPublisher();
    store.changePublisher = publisher;

    store.addSpans(
        List.of(
            span("t1", "a", qitsAttributes("repo", "wt-a"), 1),
            span("t2", "b", qitsAttributes("repo", "wt-b"), 2)));

    assertEquals(2, publisher.fired.size());
    assertTrue(publisher.fired.stream().anyMatch(h -> h.workspaceId().equals("wt-a")));
    assertTrue(publisher.fired.stream().anyMatch(h -> h.workspaceId().equals("wt-b")));
  }

  @Test
  void unscopedTelemetryFiresNoHint() {
    RecordingPublisher publisher = new RecordingPublisher();
    store.changePublisher = publisher;

    // No qits.repository.id / qits.workspace.id → lands in the unscoped bucket, nothing subscribes.
    store.addLogs(List.of(log("orphan", Map.of("service.name", "svc"), 1)));

    assertTrue(publisher.fired.isEmpty());
  }

  @Test
  void bucketsAreIsolatedByWorkspace() {
    store.addSpans(List.of(span("t1", "a", qitsAttributes("repoA", "wt1"), 1)));
    store.addSpans(List.of(span("t2", "b", qitsAttributes("repoB", "wt2"), 2)));

    assertEquals(1, store.spans("repoA", "wt1").size());
    assertEquals("a", store.spans("repoA", "wt1").getFirst().name());
    assertEquals(1, store.spans("repoB", "wt2").size());
    assertTrue(store.spans("repoA", "wt2").isEmpty());
  }

  @Test
  void spanCapEvictsOldestAndPrunesTraceIndex() {
    store.maxSpansPerWorkspace = 3;
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
    store.maxSpansPerWorkspace = 1;
    store.maxLogsPerWorkspace = 1;
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

    assertEquals(1, store.logs("repo", "quiet").size(), "quiet workspace lost telemetry");
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
    store.maxMetricSeriesPerWorkspace = 2;
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
  void unattributedTelemetryIsQuarantinedNotVisibleToAnyWorkspace() {
    store.addSpans(List.of(span("t", "unscoped", Map.of("service.name", "svc"), 1)));

    assertTrue(store.spans("repo", "wt").isEmpty());
    assertTrue(store.totalBytes() > 0, "unscoped telemetry must still be retained (and bounded)");
  }

  @Test
  void telemetryWithoutTheQitsPairIsBucketedByServiceName() {
    store.addSpans(List.of(serviceSpan("t1", "from-ci", "qits-ci", 1)));
    store.addSpans(List.of(serviceSpan("t2", "from-cd", "qits-cd", 2)));

    assertEquals(1, store.spansIn("_service/qits-ci").size());
    assertEquals("from-ci", store.spansIn("_service/qits-ci").getFirst().name());
    assertEquals(1, store.spansIn("_service/qits-cd").size());
    assertTrue(store.spansIn(TelemetryStore.UNSCOPED_KEY).isEmpty(), "service.name was present");
  }

  @Test
  void telemetryWithNeitherPairNorServiceNameStillLandsInTheUnscopedBucket() {
    store.addSpans(List.of(span("t", "nameless", Map.of(), 1)));
    store.addLogs(List.of(log("nameless", Map.of("service.name", " "), 2)));

    assertEquals(1, store.spansIn(TelemetryStore.UNSCOPED_KEY).size());
    assertEquals(1, store.logsIn(TelemetryStore.UNSCOPED_KEY).size());
  }

  @Test
  void theQitsPairStillWinsOverServiceName() {
    store.addSpans(List.of(span("t", "scoped", qitsAttributes("repo", "wt"), 1)));

    assertEquals(1, store.spans("repo", "wt").size());
    assertTrue(store.spansIn("_service/svc").isEmpty(), "the pair must take precedence");
  }

  @Test
  void oneServiceCannotEvictAnotherNowThatEachHasItsOwnBucket() {
    store.maxSpansPerWorkspace = 3;
    Map<String, String> chatty = Map.of("service.name", "qits-gateway");
    Map<String, String> quiet = Map.of("service.name", "qits-cd");
    store.addSpans(List.of(span("t-quiet", "quiet", quiet, 1)));
    for (int i = 0; i < 20; i++) {
      store.addSpans(List.of(span("t-chatty-" + i, "chatty-" + i, chatty, 10 + i)));
    }

    // The cap is per source, so the chatty one pays its own bill and the quiet one keeps its span.
    assertEquals(3, store.spansIn("_service/qits-gateway").size());
    assertEquals(1, store.spansIn("_service/qits-cd").size());
    assertEquals(17, store.evictedSpans());
  }

  @Test
  void evictionCountersCountWhatWasDropped() {
    store.maxSpansPerWorkspace = 1;
    store.maxLogsPerWorkspace = 1;
    store.maxMetricSeriesPerWorkspace = 1;
    Map<String, String> attrs = qitsAttributes("repo", "wt");
    for (int i = 0; i < 4; i++) {
      store.addSpans(List.of(span("t" + i, "s" + i, attrs, i)));
      store.addLogs(List.of(log("l" + i, attrs, i)));
      store.addMetrics(List.of(metric("m" + i, i, Map.of(), attrs)));
    }

    assertEquals(3, store.evictedSpans());
    assertEquals(3, store.evictedLogs());
    assertEquals(3, store.droppedMetricSeries(), "new series over the cap are dropped, not evicted");

    store.clear();
    assertEquals(0, store.evictedSpans());
    assertEquals(0, store.evictedLogs());
    assertEquals(0, store.droppedMetricSeries());
  }

  @Test
  void sourcesReportEveryBucketWithItsCountsAndAgeSpan() {
    store.addSpans(List.of(serviceSpan("t1", "a", "qits-ci", 100)));
    store.addSpans(List.of(serviceSpan("t2", "b", "qits-ci", 300)));
    store.addLogs(List.of(log("hello", Map.of("service.name", "qits-ci"), 200)));
    store.addSpans(List.of(span("t3", "c", qitsAttributes("repo", "wt"), 50)));
    store.addSpans(List.of(span("t4", "d", Map.of(), 400)));

    List<StoredSource> sources = store.sources();
    assertEquals(3, sources.size());
    assertEquals(
        List.of("_service/qits-ci", "_unscoped", "repo/wt"),
        sources.stream().map(StoredSource::key).toList(),
        "sources are listed in key order");

    StoredSource ci = sources.getFirst();
    assertEquals(2, ci.spans());
    assertEquals(1, ci.logs());
    assertEquals(100L, ci.oldestReceivedAtMillis(), "the oldest record in the bucket");
    assertEquals(300L, ci.newestReceivedAtMillis(), "the newest record in the bucket");
    assertTrue(ci.bytes() > 0);

    // The breakdown splits by the record's own service name, per signal — the log helper reports
    // "svc", so this bucket honestly holds two.
    assertEquals(List.of("qits-ci", "svc"), ci.services().stream().map(s -> s.name()).toList());
    assertEquals(2, ci.services().getFirst().spans());
    assertEquals(0, ci.services().getFirst().logs());
    assertEquals(1, ci.services().getLast().logs());
  }

  @Test
  void anEmptiedBucketIsStillListedSoItsSilenceIsDistinguishable() {
    store.maxSpansPerWorkspace = 1;
    store.addSpans(List.of(span("t1", "a", Map.of("service.name", "qits-ci"), 1)));
    store.maxSpansPerWorkspace = 0;
    store.addSpans(List.of(span("t2", "b", Map.of("service.name", "qits-ci"), 2)));

    List<StoredSource> sources = store.sources();
    assertEquals(1, sources.size());
    assertEquals(0, sources.getFirst().spans());
    assertEquals(null, sources.getFirst().oldestReceivedAtMillis(), "nothing left to be old");
    assertTrue(store.evictedSpans() > 0, "the counter is what says the silence is eviction");
  }

  /**
   * §1.4 of the observability-UI plan argued from arithmetic that the count caps bind before the
   * byte ceiling, and the span cap was lowered to 2,000 on that basis. This turns the argument into
   * an assertion against the real estimator: if a future change makes spans fatter, or the caps
   * rise, the ceiling stops being unreachable and the "report counts, not bytes" advice in the DTO
   * javadoc — and the UI built on it — goes wrong quietly.
   */
  @Test
  void theCountCapsBindBeforeTheGlobalByteCeiling() {
    // A Quarkus server span as the platform actually exports one: ~10 span attributes on top of
    // ~8 resource attributes, http-route-shaped names.
    Map<String, String> resource =
        Map.of(
            "service.name", "qits-observability",
            "service.version", "1.0.0-SNAPSHOT",
            "telemetry.sdk.name", "opentelemetry",
            "telemetry.sdk.language", "java",
            "telemetry.sdk.version", "1.54.0",
            "host.name", "qits-cd-qits-qits-observability-bdc0983f",
            "os.type", "linux",
            "process.runtime.name", "GraalVM Native Image");
    Map<String, String> attributes =
        Map.of(
            "http.request.method", "POST",
            "url.path", "/observability/api/otel/v1/traces",
            "url.scheme", "http",
            "http.response.status_code", "200",
            "http.route", "/observability/api/otel/v1/traces",
            "server.address", "qits-observability",
            "server.port", "8080",
            "network.protocol.version", "1.1",
            "user_agent.original", "OTel-OTLP-Exporter-Java/1.54.0",
            "client.address", "172.18.0.5");
    StoredSpan realistic =
        new StoredSpan(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            "c8ad6b7169203332",
            "qits-observability",
            "io.quarkus.opentelemetry",
            "POST /observability/api/otel/v1/traces",
            "SERVER",
            1_000_000_000L,
            1_250_000_000L,
            "UNSET",
            "",
            attributes,
            List.of(),
            resource,
            1L);

    int spanBytes = TelemetrySizeEstimator.bytesOf(realistic);
    // Ten platform processes, each its own bucket since the service.name re-bucketing.
    long worstCase = 10L * store.maxSpansPerWorkspace * spanBytes;

    assertEquals(2000, store.maxSpansPerWorkspace, "the plan's cap");
    assertTrue(
        worstCase < store.maxTotalBytes,
        "ten full span buckets estimate at "
            + worstCase
            + " bytes, which must stay under the "
            + store.maxTotalBytes
            + "-byte ceiling — otherwise the ceiling binds first and the cap needs revisiting");
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
