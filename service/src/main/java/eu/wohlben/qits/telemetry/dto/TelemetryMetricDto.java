package eu.wohlben.qits.telemetry.dto;

import java.util.Map;

/**
 * The latest point of one metric series, as returned by the telemetry query surface.
 *
 * <p>{@code resourceAttributes} carries the emitting resource whole, for the reason and with the
 * caveats written on {@link TelemetryLogDto}: a number is only comparable across a deploy if the
 * reader can see which build produced it.
 */
public record TelemetryMetricDto(
    String name,
    String description,
    String unit,
    String type,
    double value,
    long epochNanos,
    String serviceName,
    Map<String, String> attributes,
    Map<String, String> resourceAttributes) {

  public static TelemetryMetricDto of(MetricPoint point) {
    return new TelemetryMetricDto(
        point.name(),
        point.description(),
        point.unit(),
        point.type(),
        point.value(),
        point.epochNanos(),
        point.serviceName(),
        point.attributes(),
        point.resourceAttributes());
  }
}
