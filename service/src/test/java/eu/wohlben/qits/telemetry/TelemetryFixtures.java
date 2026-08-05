package eu.wohlben.qits.telemetry;

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

  // --- the canary ---------------------------------------------------------------------------
  // One batch shaped like what a platform Quarkus service's OTel logging bridge exports: a
  // resource that names the service, the build and the instance, two records inside one server
  // span, and an error carrying the OTel exception semantic attributes rather than only a
  // formatted body. Used by CanaryLogStreamTest against the running suite and by OtelReceiverIT
  // against the packaged artifact, so both prove the same payload.

  /** The canary's {@code service.name} — what the source list must show instead of {@code _unscoped}. */
  public static final String CANARY_SERVICE = "qits-canary";

  /** One instance attribute beside the name: identity is a resource concern, not a message prefix. */
  public static final String CANARY_INSTANCE_ID = "qits-canary-01f9";

  /**
   * The canary's {@code service.version}. cd stamps a release version into every container it
   * deploys, and it is the attribute that separates "this service is failing" from "this release
   * is failing" — so the fixture carries one, and the read surface has to bring it back.
   */
  public static final String CANARY_VERSION = "2026.805.114500";

  /** The canary's {@code deployment.environment.name}, the third attribute cd injects. */
  public static final String CANARY_ENVIRONMENT = "production";

  public static final String CANARY_TRACE_ID = "4e1c9a2b7d6f43a8b0c5e8f1a2d3c4b5";
  public static final String CANARY_SPAN_ID = "5f2da3c8e7b09142";

  public static final String CANARY_EXCEPTION_TYPE = "java.lang.IllegalStateException";
  public static final String CANARY_EXCEPTION_MESSAGE = "canary could not reach the widget service";

  /** A real multi-frame stack trace: the errors feed has to carry it through unchanged. */
  public static final String CANARY_STACKTRACE =
      "java.lang.IllegalStateException: canary could not reach the widget service\n"
          + "\tat eu.wohlben.qits.canary.CanaryResource.callWidgets(CanaryResource.java:42)\n"
          + "\tat eu.wohlben.qits.canary.CanaryResource.get(CanaryResource.java:28)\n";

  private TelemetryFixtures() {}

  /**
   * The canary's resource: the service name plus the three identity attributes a cd-deployed
   * container carries — version, environment and instance — and no qits.* pair, which is what every
   * platform process actually exports.
   */
  public static Resource canaryResource() {
    return Resource.newBuilder()
        .addAttributes(attribute("service.name", CANARY_SERVICE))
        .addAttributes(attribute("service.version", CANARY_VERSION))
        .addAttributes(attribute("deployment.environment.name", CANARY_ENVIRONMENT))
        .addAttributes(attribute("service.instance.id", CANARY_INSTANCE_ID))
        .build();
  }

  /**
   * The canary log batch: one INFO record and one ERROR record, both stamped with the canary trace
   * and span so the trace-scoped read has something to correlate, the ERROR additionally carrying
   * {@code exception.type} / {@code exception.message} / {@code exception.stacktrace}.
   *
   * <p>Timestamps are wall-clock at build time, not the fixed nanos the other fixtures use: the
   * query surface filters on its own ingest stamp, but a screen renders these, and a record dated
   * 1970 would pass every assertion while looking broken.
   */
  public static ExportLogsServiceRequest canaryLogsRequest(String infoBody, String errorBody) {
    long nowNanos = System.currentTimeMillis() * 1_000_000L;
    LogRecord info =
        canaryRecord(nowNanos, SeverityNumber.SEVERITY_NUMBER_INFO, "INFO", infoBody).build();
    LogRecord error =
        canaryRecord(nowNanos + 12_000_000L, SeverityNumber.SEVERITY_NUMBER_ERROR, "ERROR", errorBody)
            .addAttributes(attribute("exception.type", CANARY_EXCEPTION_TYPE))
            .addAttributes(attribute("exception.message", CANARY_EXCEPTION_MESSAGE))
            .addAttributes(attribute("exception.stacktrace", CANARY_STACKTRACE))
            .build();
    return ExportLogsServiceRequest.newBuilder()
        .addResourceLogs(
            ResourceLogs.newBuilder()
                .setResource(canaryResource())
                .addScopeLogs(
                    ScopeLogs.newBuilder()
                        .setScope(
                            InstrumentationScope.newBuilder().setName("io.quarkus.opentelemetry"))
                        .addLogRecords(info)
                        .addLogRecords(error)))
        .build();
  }

  /** The server span the canary's records were emitted inside — what its trace page is a page of. */
  public static ExportTraceServiceRequest canaryTraceRequest() {
    long startNanos = System.currentTimeMillis() * 1_000_000L;
    return traceRequest(
        canaryResource(),
        spanBuilder(
                CANARY_TRACE_ID,
                CANARY_SPAN_ID,
                "GET /canary",
                startNanos,
                startNanos + 42_000_000L)
            .build());
  }

  private static LogRecord.Builder canaryRecord(
      long epochNanos, SeverityNumber severity, String severityText, String body) {
    return LogRecord.newBuilder()
        .setTimeUnixNano(epochNanos)
        .setObservedTimeUnixNano(epochNanos)
        .setSeverityNumber(severity)
        .setSeverityText(severityText)
        .setBody(AnyValue.newBuilder().setStringValue(body))
        .setTraceId(bytes(CANARY_TRACE_ID))
        .setSpanId(bytes(CANARY_SPAN_ID))
        .addAttributes(attribute("thread.name", "executor-thread-1"))
        .addAttributes(attribute("log.logger.namespace", "eu.wohlben.qits.canary.CanaryResource"));
  }

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
