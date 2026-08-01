package eu.wohlben.qits.telemetry.dto;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The buffer's own state, as returned by {@code GET /telemetry/store} — the numbers a UI needs to
 * say what it is showing without overclaiming.
 *
 * <p>{@code startedAt} is when this buffer began holding what it holds. It is not a build date or a
 * deployment time: the store is in memory and a restart empties it, so this is the honest lower
 * bound on the age of anything in here.
 *
 * <p>{@code evictedSpans} is the load-bearing one. Zero means the answers below are everything that
 * arrived; non-zero means they are what survived, and a screen that does not say so is inviting the
 * wrong conclusion from a short list.
 *
 * <p>Report pressure from the <em>counts</em>, not from {@code totalBytes}: with the shipped caps
 * the count caps bind long before the byte ceiling, so a byte gauge sits low and still.
 */
@Schema(name = "TelemetryStoreState", description = "The in-memory buffer's own state.")
public record TelemetryStoreStateDto(
    Instant startedAt,
    long totalBytes,
    long maxTotalBytes,
    Caps caps,
    int sourceCount,
    long evictedSpans,
    long evictedLogs,
    long droppedMetricSeries) {

  /** The per-source count caps in force. */
  @Schema(name = "TelemetryStoreCaps")
  public record Caps(int spansPerSource, int logsPerSource, int metricSeriesPerSource) {}
}
