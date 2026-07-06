package eu.wohlben.qits.domain.telemetry;

import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.InstrumentationScope;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

/**
 * Builds OTLP protobuf export requests with the same proto bindings the receiver decodes with — the
 * wire-accurate fixtures for receiver, decoder and query tests.
 */
public final class TelemetryFixtures {

  public static final String TRACE_ID_A = "0af7651916cd43dd8448eb211c80319c";
  public static final String TRACE_ID_B = "1bf7651916cd43dd8448eb211c80319d";
  public static final String SPAN_ID_A = "b7ad6b7169203331";
  public static final String SPAN_ID_B = "c8ad6b7169203332";

  private TelemetryFixtures() {}

  public static Resource resource(String serviceName, String repoId, String workspaceId) {
    Resource.Builder resource =
        Resource.newBuilder().addAttributes(attribute("service.name", serviceName));
    if (repoId != null) {
      resource.addAttributes(attribute("qits.repository.id", repoId));
    }
    if (workspaceId != null) {
      resource.addAttributes(attribute("qits.workspace.id", workspaceId));
    }
    return resource.build();
  }

  public static KeyValue attribute(String key, String value) {
    return KeyValue.newBuilder()
        .setKey(key)
        .setValue(AnyValue.newBuilder().setStringValue(value))
        .build();
  }

  /** An ERROR-status span carrying an OTel {@code exception} event, in a full trace request. */
  public static ExportTraceServiceRequest errorTraceRequest(
      String serviceName, String repoId, String workspaceId, String traceId, String spanId) {
    Span span =
        spanBuilder(traceId, spanId, "GET /boom")
            .setStatus(
                Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).setMessage("boom"))
            .addEvents(
                Span.Event.newBuilder()
                    .setName("exception")
                    .setTimeUnixNano(1_000_000_500L)
                    .addAttributes(attribute("exception.type", "java.lang.IllegalStateException"))
                    .addAttributes(attribute("exception.message", "boom"))
                    .addAttributes(attribute("exception.stacktrace", "at eu.example.Boom.go")))
            .build();
    return traceRequest(resource(serviceName, repoId, workspaceId), span);
  }

  /** A plain OK span, in a full trace request. */
  public static ExportTraceServiceRequest okTraceRequest(
      String serviceName, String repoId, String workspaceId, String traceId, String spanId) {
    return traceRequest(
        resource(serviceName, repoId, workspaceId),
        spanBuilder(traceId, spanId, "GET /fine").build());
  }

  public static ExportTraceServiceRequest traceRequest(Resource resource, Span... spans) {
    ScopeSpans.Builder scope =
        ScopeSpans.newBuilder()
            .setScope(InstrumentationScope.newBuilder().setName("test-instrumentation"));
    for (Span span : spans) {
      scope.addSpans(span);
    }
    return ExportTraceServiceRequest.newBuilder()
        .addResourceSpans(ResourceSpans.newBuilder().setResource(resource).addScopeSpans(scope))
        .build();
  }

  public static Span.Builder spanBuilder(String traceId, String spanId, String name) {
    return spanBuilder(traceId, spanId, name, 1_000_000_000L, 1_250_000_000L);
  }

  /** Like {@link #spanBuilder(String, String, String)} but with explicit start/end times. */
  public static Span.Builder spanBuilder(
      String traceId, String spanId, String name, long startNanos, long endNanos) {
    return Span.newBuilder()
        .setTraceId(bytes(traceId))
        .setSpanId(bytes(spanId))
        .setName(name)
        .setKind(Span.SpanKind.SPAN_KIND_SERVER)
        .setStartTimeUnixNano(startNanos)
        .setEndTimeUnixNano(endNanos);
  }

  /** A single log record in a full logs request. */
  public static ExportLogsServiceRequest logsRequest(
      String serviceName,
      String repoId,
      String workspaceId,
      SeverityNumber severity,
      String body,
      String traceId) {
    LogRecord.Builder log =
        LogRecord.newBuilder()
            .setTimeUnixNano(1_000_000_000L)
            .setSeverityNumber(severity)
            .setSeverityText(severity.name().replace("SEVERITY_NUMBER_", ""))
            .setBody(AnyValue.newBuilder().setStringValue(body));
    if (traceId != null) {
      log.setTraceId(bytes(traceId));
    }
    return ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(
            ResourceLogs.newBuilder()
                .setResource(resource(serviceName, repoId, workspaceId))
                .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(log)))
        .build();
  }

  /** One gauge and one sum ("counter") metric in a full metrics request. */
  public static ExportMetricsServiceRequest metricsRequest(
      String serviceName, String repoId, String workspaceId, double gaugeValue, long counterValue) {
    Metric gauge =
        Metric.newBuilder()
            .setName("jvm.memory.used")
            .setUnit("By")
            .setGauge(
                Gauge.newBuilder()
                    .addDataPoints(
                        NumberDataPoint.newBuilder()
                            .setTimeUnixNano(1_000_000_000L)
                            .setAsDouble(gaugeValue)
                            .addAttributes(attribute("pool", "heap"))))
            .build();
    Metric counter =
        Metric.newBuilder()
            .setName("http.server.requests")
            .setSum(
                Sum.newBuilder()
                    .setIsMonotonic(true)
                    .addDataPoints(
                        NumberDataPoint.newBuilder()
                            .setTimeUnixNano(1_000_000_000L)
                            .setAsInt(counterValue)))
            .build();
    return ExportMetricsServiceRequest.newBuilder()
        .addResourceMetrics(
            ResourceMetrics.newBuilder()
                .setResource(resource(serviceName, repoId, workspaceId))
                .addScopeMetrics(ScopeMetrics.newBuilder().addMetrics(gauge).addMetrics(counter)))
        .build();
  }

  public static byte[] gzip(byte[] data) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
        gz.write(data);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static ByteString bytes(String hex) {
    return ByteString.copyFrom(HexFormat.of().parseHex(hex));
  }
}
