package eu.wohlben.qits.domain.telemetry.dto;

import java.util.List;

/** A full trace: its buffered spans (flat, parent-annotated) plus the logs correlated to it. */
public record TelemetryTraceDto(
    String traceId, List<TelemetrySpanDto> spans, List<TelemetryLogDto> logs) {}
