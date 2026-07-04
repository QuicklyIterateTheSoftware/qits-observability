package eu.wohlben.qits.domain.telemetry.dto;

import java.util.List;
import java.util.Map;

/**
 * A span as held in the in-memory telemetry store: the slim, protobuf-free projection of an OTLP
 * span. Ids are lowercase hex (OTLP/JSON style), {@code kind} and {@code status} are the enum names
 * without their {@code SPAN_KIND_} / {@code STATUS_CODE_} prefixes. {@code receivedAtMillis} is
 * stamped by the receiver from the server clock — all time-window queries use it, never the
 * exporter's own timestamps, so a container with a skewed clock can't hide or time-travel its
 * telemetry.
 */
public record StoredSpan(
    String traceId,
    String spanId,
    String parentSpanId,
    String serviceName,
    String scopeName,
    String name,
    String kind,
    long startEpochNanos,
    long endEpochNanos,
    String status,
    String statusMessage,
    Map<String, String> attributes,
    List<SpanEvent> events,
    Map<String, String> resourceAttributes,
    long receivedAtMillis) {

  public long durationMs() {
    return (endEpochNanos - startEpochNanos) / 1_000_000;
  }

  public boolean isError() {
    return "ERROR".equals(status);
  }

  public boolean hasExceptionEvent() {
    return events.stream().anyMatch(SpanEvent::isException);
  }
}
