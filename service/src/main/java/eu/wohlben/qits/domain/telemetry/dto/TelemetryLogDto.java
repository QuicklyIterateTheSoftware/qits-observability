package eu.wohlben.qits.domain.telemetry.dto;

import java.util.Map;

/** A log record as returned by the telemetry query surface (MCP tools and REST twins). */
public record TelemetryLogDto(
    long epochNanos,
    int severityNumber,
    String severityText,
    String body,
    String traceId,
    String spanId,
    String serviceName,
    Map<String, String> attributes) {

  public static TelemetryLogDto of(StoredLog log) {
    return new TelemetryLogDto(
        log.epochNanos(),
        log.severityNumber(),
        log.severityText(),
        log.body(),
        log.traceId(),
        log.spanId(),
        log.serviceName(),
        log.attributes());
  }
}
