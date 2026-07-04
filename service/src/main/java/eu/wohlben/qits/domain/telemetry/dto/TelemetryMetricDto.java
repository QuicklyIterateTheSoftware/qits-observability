package eu.wohlben.qits.domain.telemetry.dto;

import java.util.Map;

/** The latest point of one metric series, as returned by the telemetry query surface. */
public record TelemetryMetricDto(
    String name,
    String description,
    String unit,
    String type,
    double value,
    long epochNanos,
    String serviceName,
    Map<String, String> attributes) {

  public static TelemetryMetricDto of(MetricPoint point) {
    return new TelemetryMetricDto(
        point.name(),
        point.description(),
        point.unit(),
        point.type(),
        point.value(),
        point.epochNanos(),
        point.serviceName(),
        point.attributes());
  }
}
