package eu.wohlben.qits.telemetry.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One bucket of the in-memory telemetry store as the store itself sees it: the raw key, the counts,
 * the estimated bytes and the millisecond ingest stamps of the oldest and newest record it holds.
 *
 * <p>The slim projection, in the spirit of {@link StoredSpan}: no interpretation of the key and no
 * formatted time. {@code TelemetryQueryService} turns this into {@code TelemetrySourceDto}, which is
 * where the key becomes a kind and a label and the stamps become instants.
 *
 * <p>{@code oldestReceivedAtMillis} / {@code newestReceivedAtMillis} are null when the bucket holds
 * nothing. A UI needs them to tell "your window excludes what is buffered" from "there is nothing
 * buffered", which are the same empty list otherwise.
 */
public record StoredSource(
    String key,
    List<Service> services,
    int spans,
    int logs,
    int metricSeries,
    long bytes,
    Long oldestReceivedAtMillis,
    Long newestReceivedAtMillis) {

  /**
   * One {@code service.name}'s share of a bucket. A workspace bucket can hold several (the dev
   * server and whatever it launches); a service bucket holds exactly one by construction.
   *
   * <p>This one record does reach the wire, inside {@code TelemetrySourceDto} — the counts need no
   * translation, so there is nothing for a separate DTO to do.
   */
  @Schema(name = "TelemetrySourceService")
  public record Service(String name, int spans, int logs, int metricSeries) {}
}
