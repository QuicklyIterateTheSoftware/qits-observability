package eu.wohlben.qits.telemetry.dto;

import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One addressable bucket of the telemetry buffer, as returned by {@code GET /telemetry/sources}.
 *
 * <p>{@code key} is <em>opaque</em>: hand it back verbatim as {@code ?source=} and every read
 * endpoint answers from this bucket. That is the whole reason it exists — the repository/workspace
 * pair cannot name a bucket that was keyed on a service name, and a caller inventing a key from
 * {@code kind} and {@code label} would be guessing at a private encoding.
 *
 * <p>{@code repositoryId} and {@code workspaceId} are non-null only for a {@code WORKSPACE} source,
 * where they are the pair the exporter stamped.
 */
@Schema(name = "TelemetrySource", description = "One addressable bucket of the telemetry buffer.")
public record TelemetrySourceDto(
    String key,
    Kind kind,
    String label,
    String repositoryId,
    String workspaceId,
    List<StoredSource.Service> services,
    int spans,
    int logs,
    int metricSeries,
    long bytes,
    Instant oldestReceivedAt,
    Instant newestReceivedAt) {

  /** What a bucket was keyed on. */
  @Schema(name = "TelemetrySourceKind")
  public enum Kind {
    /** Keyed on a {@code service.name} — a platform process exporting its own telemetry. */
    SERVICE,
    /** Keyed on the {@code qits.repository.id} / {@code qits.workspace.id} pair. */
    WORKSPACE,
    /** Keyed on nothing: the record carried neither the pair nor a service name. */
    UNSCOPED
  }
}
