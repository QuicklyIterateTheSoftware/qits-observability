package eu.wohlben.qits.domain.telemetry.dto;

import java.util.List;

/**
 * One trace's worth of error evidence: its error-status spans (including any {@code exception}
 * events with structured stack traces) and its ERROR-severity logs. Entries with no trace
 * correlation are collected under an empty {@code traceId}.
 */
public record TelemetryErrorGroupDto(
    String traceId,
    String serviceName,
    List<TelemetrySpanDto> errorSpans,
    List<TelemetryLogDto> errorLogs) {}
