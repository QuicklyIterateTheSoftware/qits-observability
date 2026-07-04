package eu.wohlben.qits.domain.telemetry.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.telemetry.TelemetryFixtures;
import eu.wohlben.qits.domain.telemetry.dto.MetricPoint;
import eu.wohlben.qits.domain.telemetry.dto.StoredLog;
import eu.wohlben.qits.domain.telemetry.dto.StoredSpan;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;

class TelemetryDecoderTest {

  private final TelemetryDecoder decoder = new TelemetryDecoder();

  @Test
  void decodesErrorSpanWithExceptionEventAndResourceAttributes() {
    List<StoredSpan> spans =
        decoder.decodeSpans(
            TelemetryFixtures.errorTraceRequest(
                "my-service",
                "repo-1",
                "wt-1",
                TelemetryFixtures.TRACE_ID_A,
                TelemetryFixtures.SPAN_ID_A),
            42L);

    assertEquals(1, spans.size());
    StoredSpan span = spans.getFirst();
    assertEquals(TelemetryFixtures.TRACE_ID_A, span.traceId());
    assertEquals(TelemetryFixtures.SPAN_ID_A, span.spanId());
    assertEquals("", span.parentSpanId());
    assertEquals("my-service", span.serviceName());
    assertEquals("test-instrumentation", span.scopeName());
    assertEquals("SERVER", span.kind());
    assertEquals("ERROR", span.status());
    assertEquals("boom", span.statusMessage());
    assertEquals("repo-1", span.resourceAttributes().get("qits.repository.id"));
    assertEquals("wt-1", span.resourceAttributes().get("qits.worktree.id"));
    assertEquals(42L, span.receivedAtMillis());
    assertTrue(span.isError());
    assertTrue(span.hasExceptionEvent());
    assertEquals(
        "java.lang.IllegalStateException",
        span.events().getFirst().attributes().get("exception.type"));
  }

  @Test
  void decodesLogWithTraceCorrelationAndObservedTimeFallback() {
    List<StoredLog> logs =
        decoder.decodeLogs(
            TelemetryFixtures.logsRequest(
                "my-service",
                "repo-1",
                "wt-1",
                SeverityNumber.SEVERITY_NUMBER_ERROR,
                "it broke",
                TelemetryFixtures.TRACE_ID_A),
            42L);
    assertEquals(1, logs.size());
    StoredLog log = logs.getFirst();
    assertEquals("it broke", log.body());
    assertEquals(17, log.severityNumber());
    assertTrue(log.isError());
    assertEquals(TelemetryFixtures.TRACE_ID_A, log.traceId());
    assertTrue(log.hasTrace());
    assertEquals("my-service", log.serviceName());

    // A record with no timeUnixNano falls back to observedTimeUnixNano.
    ExportLogsServiceRequest observedOnly =
        ExportLogsServiceRequest.newBuilder()
            .addResourceLogs(
                ResourceLogs.newBuilder()
                    .setResource(TelemetryFixtures.resource("svc", "r", "w"))
                    .addScopeLogs(
                        ScopeLogs.newBuilder()
                            .addLogRecords(
                                LogRecord.newBuilder()
                                    .setObservedTimeUnixNano(777L)
                                    .setBody(AnyValue.newBuilder().setStringValue("late")))))
            .build();
    assertEquals(777L, decoder.decodeLogs(observedOnly, 1L).getFirst().epochNanos());
  }

  @Test
  void decodesGaugeAndSumDropsHistogram() {
    ExportMetricsServiceRequest request =
        TelemetryFixtures.metricsRequest("my-service", "repo-1", "wt-1", 12.5, 300);
    List<MetricPoint> points = decoder.decodeMetrics(request, 42L);

    assertEquals(2, points.size());
    MetricPoint gauge = points.getFirst();
    assertEquals("jvm.memory.used", gauge.name());
    assertEquals("GAUGE", gauge.type());
    assertEquals(12.5, gauge.value());
    assertEquals("heap", gauge.attributes().get("pool"));
    MetricPoint counter = points.getLast();
    assertEquals("http.server.requests", counter.name());
    assertEquals("COUNTER", counter.type());
    assertEquals(300.0, counter.value());

    ExportMetricsServiceRequest histogramOnly =
        ExportMetricsServiceRequest.newBuilder()
            .addResourceMetrics(
                ResourceMetrics.newBuilder()
                    .setResource(TelemetryFixtures.resource("svc", "r", "w"))
                    .addScopeMetrics(
                        ScopeMetrics.newBuilder()
                            .addMetrics(
                                Metric.newBuilder()
                                    .setName("latency")
                                    .setHistogram(Histogram.getDefaultInstance()))))
            .build();
    assertTrue(decoder.decodeMetrics(histogramOnly, 1L).isEmpty());
  }

  @Test
  void flattensStructuredAttributeValues() {
    AnyValue array =
        AnyValue.newBuilder()
            .setArrayValue(
                ArrayValue.newBuilder()
                    .addValues(AnyValue.newBuilder().setIntValue(1))
                    .addValues(AnyValue.newBuilder().setStringValue("two")))
            .build();
    ExportLogsServiceRequest request =
        ExportLogsServiceRequest.newBuilder()
            .addResourceLogs(
                ResourceLogs.newBuilder()
                    .setResource(TelemetryFixtures.resource("svc", "r", "w"))
                    .addScopeLogs(
                        ScopeLogs.newBuilder()
                            .addLogRecords(
                                LogRecord.newBuilder().setTimeUnixNano(1).setBody(array))))
            .build();

    assertEquals("[1,two]", decoder.decodeLogs(request, 1L).getFirst().body());
  }
}
