package eu.wohlben.qits.telemetry.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One record on the live stream ({@code /observability/stream}), as the client reads it:
 *
 * <pre>{"kind": "log|span|metric", "receivedAtMillis": …, "source": "_service/qits-ci", "record": {…}}</pre>
 *
 * <p>{@code record} is the DTO the REST query API returns for that kind ({@link TelemetryLogDto},
 * {@link TelemetrySpanDto}, {@link TelemetryMetricDto}), so a reader parses one shape on both
 * surfaces. {@code source} is the store's bucket key for the record, the same opaque key {@code
 * …/telemetry/sources} lists and {@code ?source=} takes back.
 *
 * <p>Registered for reflection with the three DTOs: Jackson reads their components by reflection,
 * and in the native image nothing else would register them for this path.
 */
@RegisterForReflection(
    targets = {
      TelemetryStreamFrame.class,
      TelemetryLogDto.class,
      TelemetrySpanDto.class,
      TelemetryMetricDto.class,
      SpanEvent.class
    })
public record TelemetryStreamFrame(
    String kind, long receivedAtMillis, String source, Object record) {}
