package eu.wohlben.qits.domain.telemetry.control;

import eu.wohlben.qits.domain.telemetry.dto.MetricPoint;
import eu.wohlben.qits.domain.telemetry.dto.SpanEvent;
import eu.wohlben.qits.domain.telemetry.dto.StoredLog;
import eu.wohlben.qits.domain.telemetry.dto.StoredSpan;
import java.util.List;
import java.util.Map;

/**
 * Approximate retained heap size of stored telemetry records, in the same spirit as the command
 * ring buffer's byte cap: cheap and deterministic, not JOL-exact. 2 bytes per string char, a flat
 * overhead per object/map entry. Deterministic matters — the store adds the estimate on append and
 * subtracts the identical value on evict, so the running totals never drift.
 */
final class TelemetrySizeEstimator {

  /** Rough per-object / per-map-entry overhead (headers, references, boxing). */
  private static final int ENTRY_OVERHEAD = 48;

  private TelemetrySizeEstimator() {}

  static int bytesOf(StoredSpan span) {
    int size = ENTRY_OVERHEAD;
    size += chars(span.traceId()) + chars(span.spanId()) + chars(span.parentSpanId());
    size += chars(span.serviceName()) + chars(span.scopeName()) + chars(span.name());
    size += chars(span.kind()) + chars(span.status()) + chars(span.statusMessage());
    size += bytesOf(span.attributes()) + bytesOf(span.resourceAttributes());
    size += bytesOf(span.events());
    return size;
  }

  static int bytesOf(StoredLog log) {
    int size = ENTRY_OVERHEAD;
    size += chars(log.severityText()) + chars(log.body());
    size += chars(log.traceId()) + chars(log.spanId()) + chars(log.serviceName());
    size += bytesOf(log.attributes()) + bytesOf(log.resourceAttributes());
    return size;
  }

  static int bytesOf(MetricPoint point) {
    int size = ENTRY_OVERHEAD;
    size += chars(point.name()) + chars(point.description()) + chars(point.unit());
    size += chars(point.type()) + chars(point.serviceName());
    size += bytesOf(point.attributes()) + bytesOf(point.resourceAttributes());
    return size;
  }

  private static int bytesOf(List<SpanEvent> events) {
    int size = 0;
    for (SpanEvent event : events) {
      size += ENTRY_OVERHEAD + chars(event.name()) + bytesOf(event.attributes());
    }
    return size;
  }

  private static int bytesOf(Map<String, String> attributes) {
    int size = 0;
    for (Map.Entry<String, String> entry : attributes.entrySet()) {
      size += ENTRY_OVERHEAD + chars(entry.getKey()) + chars(entry.getValue());
    }
    return size;
  }

  private static int chars(String value) {
    return value == null ? 0 : value.length() * 2;
  }
}
