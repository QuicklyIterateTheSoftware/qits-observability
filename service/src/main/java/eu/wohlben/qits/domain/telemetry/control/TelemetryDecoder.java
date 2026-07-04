package eu.wohlben.qits.domain.telemetry.control;

import com.google.protobuf.ByteString;
import eu.wohlben.qits.domain.telemetry.dto.MetricPoint;
import eu.wohlben.qits.domain.telemetry.dto.SpanEvent;
import eu.wohlben.qits.domain.telemetry.dto.StoredLog;
import eu.wohlben.qits.domain.telemetry.dto.StoredSpan;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Decodes OTLP protobuf export requests into the store's slim records — the only class in the
 * codebase that touches {@code io.opentelemetry.proto.*}. Deliberately lossy: span links, dropped
 * counts, flags, trace state, schema URLs, scope attributes/version, exemplars and every metric
 * type except Gauge and Sum are discarded (the spec defers them), keeping the retained footprint
 * small and the records plain.
 */
@ApplicationScoped
public class TelemetryDecoder {

  private static final String SERVICE_NAME_ATTRIBUTE = "service.name";
  private static final HexFormat HEX = HexFormat.of();

  /** Decodes a trace export; {@code receivedAtMillis} is the server-clock ingest stamp. */
  public List<StoredSpan> decodeSpans(ExportTraceServiceRequest request, long receivedAtMillis) {
    List<StoredSpan> decoded = new ArrayList<>();
    for (ResourceSpans resourceSpans : request.getResourceSpansList()) {
      Map<String, String> resourceAttributes =
          attributes(resourceSpans.getResource().getAttributesList());
      String serviceName = resourceAttributes.getOrDefault(SERVICE_NAME_ATTRIBUTE, "");
      for (ScopeSpans scopeSpans : resourceSpans.getScopeSpansList()) {
        String scopeName = scopeSpans.getScope().getName();
        for (Span span : scopeSpans.getSpansList()) {
          decoded.add(
              new StoredSpan(
                  hex(span.getTraceId()),
                  hex(span.getSpanId()),
                  hex(span.getParentSpanId()),
                  serviceName,
                  scopeName,
                  span.getName(),
                  stripPrefix(span.getKind().name(), "SPAN_KIND_"),
                  span.getStartTimeUnixNano(),
                  span.getEndTimeUnixNano(),
                  stripPrefix(span.getStatus().getCode().name(), "STATUS_CODE_"),
                  span.getStatus().getMessage(),
                  attributes(span.getAttributesList()),
                  events(span.getEventsList()),
                  resourceAttributes,
                  receivedAtMillis));
        }
      }
    }
    return decoded;
  }

  /** Decodes a logs export; {@code receivedAtMillis} is the server-clock ingest stamp. */
  public List<StoredLog> decodeLogs(ExportLogsServiceRequest request, long receivedAtMillis) {
    List<StoredLog> decoded = new ArrayList<>();
    for (ResourceLogs resourceLogs : request.getResourceLogsList()) {
      Map<String, String> resourceAttributes =
          attributes(resourceLogs.getResource().getAttributesList());
      String serviceName = resourceAttributes.getOrDefault(SERVICE_NAME_ATTRIBUTE, "");
      for (ScopeLogs scopeLogs : resourceLogs.getScopeLogsList()) {
        for (LogRecord log : scopeLogs.getLogRecordsList()) {
          long epochNanos =
              log.getTimeUnixNano() != 0 ? log.getTimeUnixNano() : log.getObservedTimeUnixNano();
          decoded.add(
              new StoredLog(
                  epochNanos,
                  log.getSeverityNumberValue(),
                  log.getSeverityText(),
                  flatten(log.getBody()),
                  hex(log.getTraceId()),
                  hex(log.getSpanId()),
                  serviceName,
                  attributes(log.getAttributesList()),
                  resourceAttributes,
                  receivedAtMillis));
        }
      }
    }
    return decoded;
  }

  /**
   * Decodes a metrics export; only Gauge and Sum data points survive (Sum as COUNTER with the
   * latest cumulative value — the store keeps one point per series anyway).
   */
  public List<MetricPoint> decodeMetrics(
      ExportMetricsServiceRequest request, long receivedAtMillis) {
    List<MetricPoint> decoded = new ArrayList<>();
    for (ResourceMetrics resourceMetrics : request.getResourceMetricsList()) {
      Map<String, String> resourceAttributes =
          attributes(resourceMetrics.getResource().getAttributesList());
      String serviceName = resourceAttributes.getOrDefault(SERVICE_NAME_ATTRIBUTE, "");
      for (ScopeMetrics scopeMetrics : resourceMetrics.getScopeMetricsList()) {
        for (Metric metric : scopeMetrics.getMetricsList()) {
          List<NumberDataPoint> points;
          String type;
          if (metric.hasGauge()) {
            points = metric.getGauge().getDataPointsList();
            type = "GAUGE";
          } else if (metric.hasSum()) {
            points = metric.getSum().getDataPointsList();
            type = "COUNTER";
          } else {
            continue; // histograms, summaries, exponential histograms: deferred by the spec
          }
          for (NumberDataPoint point : points) {
            decoded.add(
                new MetricPoint(
                    metric.getName(),
                    metric.getDescription(),
                    metric.getUnit(),
                    type,
                    point.hasAsDouble() ? point.getAsDouble() : point.getAsInt(),
                    point.getTimeUnixNano(),
                    attributes(point.getAttributesList()),
                    serviceName,
                    resourceAttributes,
                    receivedAtMillis));
          }
        }
      }
    }
    return decoded;
  }

  private static List<SpanEvent> events(List<Span.Event> events) {
    return events.stream()
        .map(
            e -> new SpanEvent(e.getName(), e.getTimeUnixNano(), attributes(e.getAttributesList())))
        .toList();
  }

  private static Map<String, String> attributes(List<KeyValue> attributes) {
    Map<String, String> flattened = new LinkedHashMap<>();
    for (KeyValue attribute : attributes) {
      flattened.put(attribute.getKey(), flatten(attribute.getValue()));
    }
    return flattened;
  }

  /** Flattens any OTLP attribute value to a display string; nested structures via short forms. */
  private static String flatten(AnyValue value) {
    return switch (value.getValueCase()) {
      case STRING_VALUE -> value.getStringValue();
      case BOOL_VALUE -> String.valueOf(value.getBoolValue());
      case INT_VALUE -> String.valueOf(value.getIntValue());
      case DOUBLE_VALUE -> String.valueOf(value.getDoubleValue());
      case BYTES_VALUE -> Base64.getEncoder().encodeToString(value.getBytesValue().toByteArray());
      case ARRAY_VALUE ->
          value.getArrayValue().getValuesList().stream()
              .map(TelemetryDecoder::flatten)
              .collect(Collectors.joining(",", "[", "]"));
      case KVLIST_VALUE ->
          value.getKvlistValue().getValuesList().stream()
              .map(kv -> kv.getKey() + "=" + flatten(kv.getValue()))
              .collect(Collectors.joining(",", "{", "}"));
      case VALUE_NOT_SET -> "";
    };
  }

  private static String hex(ByteString id) {
    return id.isEmpty() ? "" : HEX.formatHex(id.toByteArray());
  }

  private static String stripPrefix(String enumName, String prefix) {
    return enumName.startsWith(prefix) ? enumName.substring(prefix.length()) : enumName;
  }
}
