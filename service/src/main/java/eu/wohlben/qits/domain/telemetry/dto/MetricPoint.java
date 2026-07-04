package eu.wohlben.qits.domain.telemetry.dto;

import java.util.Map;

/**
 * The latest value of one metric series (name + attribute set) in the in-memory telemetry store.
 * Only gauges and sums are decoded ({@code type} GAUGE or COUNTER); a COUNTER value is the latest
 * cumulative total, no rate math. Histograms and friends are deliberately dropped at decode time.
 */
public record MetricPoint(
    String name,
    String description,
    String unit,
    String type,
    double value,
    long epochNanos,
    Map<String, String> attributes,
    String serviceName,
    Map<String, String> resourceAttributes,
    long receivedAtMillis) {

  /** The series identity: metric name plus its sorted attribute set. */
  public String seriesKey() {
    StringBuilder key = new StringBuilder(name);
    attributes.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(e -> key.append('|').append(e.getKey()).append('=').append(e.getValue()));
    return key.toString();
  }
}
