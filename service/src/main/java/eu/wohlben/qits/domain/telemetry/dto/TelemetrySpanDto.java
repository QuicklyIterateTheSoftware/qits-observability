package eu.wohlben.qits.domain.telemetry.dto;

import java.util.List;
import java.util.Map;

/** A span as returned by the telemetry query surface (MCP tools and REST twins). */
public record TelemetrySpanDto(
    String traceId,
    String spanId,
    String parentSpanId,
    String serviceName,
    String scopeName,
    String name,
    String kind,
    long startEpochNanos,
    long durationMs,
    String status,
    String statusMessage,
    Map<String, String> attributes,
    List<SpanEvent> events) {

  public static TelemetrySpanDto of(StoredSpan span) {
    return new TelemetrySpanDto(
        span.traceId(),
        span.spanId(),
        span.parentSpanId(),
        span.serviceName(),
        span.scopeName(),
        span.name(),
        span.kind(),
        span.startEpochNanos(),
        span.durationMs(),
        span.status(),
        span.statusMessage(),
        span.attributes(),
        span.events());
  }
}
