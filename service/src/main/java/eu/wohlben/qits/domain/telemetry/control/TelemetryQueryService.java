package eu.wohlben.qits.domain.telemetry.control;

import eu.wohlben.qits.domain.telemetry.dto.StoredLog;
import eu.wohlben.qits.domain.telemetry.dto.StoredSpan;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryErrorGroupDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryLogDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryMetricDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetrySpanDto;
import eu.wohlben.qits.domain.telemetry.dto.TelemetryTraceDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one query surface over the {@link TelemetryStore}, shared verbatim by the MCP tools and the
 * REST twins so agents and the UI always see the same answers. All time windows filter on {@code
 * receivedAtMillis} (the server-clock ingest stamp) — a container with a skewed clock can't dodge
 * or fake a window. A null {@code sinceMinutes} means "everything still buffered": the buffers are
 * bounded and recent by construction.
 */
@ApplicationScoped
public class TelemetryQueryService {

  @Inject TelemetryStore store;

  /**
   * Error evidence grouped by trace: error-status spans, spans carrying {@code exception} events,
   * and ERROR-severity logs. Groups are newest-first; uncorrelated entries group under an empty
   * trace id.
   */
  public List<TelemetryErrorGroupDto> errors(
      String repoId, String workspaceId, Integer sinceMinutes) {
    long cutoff = cutoff(sinceMinutes);
    Map<String, List<StoredSpan>> spansByTrace = new LinkedHashMap<>();
    Map<String, List<StoredLog>> logsByTrace = new LinkedHashMap<>();
    Map<String, Long> newestByTrace = new LinkedHashMap<>();

    for (StoredSpan span : store.spans(repoId, workspaceId)) {
      if (span.receivedAtMillis() < cutoff || !(span.isError() || span.hasExceptionEvent())) {
        continue;
      }
      spansByTrace.computeIfAbsent(span.traceId(), t -> new ArrayList<>()).add(span);
      newestByTrace.merge(span.traceId(), span.receivedAtMillis(), Math::max);
    }
    for (StoredLog log : store.logs(repoId, workspaceId)) {
      if (log.receivedAtMillis() < cutoff || !log.isError()) {
        continue;
      }
      logsByTrace.computeIfAbsent(log.traceId(), t -> new ArrayList<>()).add(log);
      newestByTrace.merge(log.traceId(), log.receivedAtMillis(), Math::max);
    }

    return newestByTrace.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .map(
            entry -> {
              String traceId = entry.getKey();
              List<StoredSpan> spans = spansByTrace.getOrDefault(traceId, List.of());
              List<StoredLog> logs = logsByTrace.getOrDefault(traceId, List.of());
              String serviceName =
                  !spans.isEmpty() ? spans.getFirst().serviceName() : logs.getFirst().serviceName();
              return new TelemetryErrorGroupDto(
                  traceId,
                  serviceName,
                  spans.stream()
                      .sorted(Comparator.comparingLong(StoredSpan::startEpochNanos))
                      .map(TelemetrySpanDto::of)
                      .toList(),
                  logs.stream()
                      .sorted(Comparator.comparingLong(StoredLog::epochNanos))
                      .map(TelemetryLogDto::of)
                      .toList());
            })
        .toList();
  }

  /** The full trace: its spans ordered by start time plus every log correlated to it. */
  public TelemetryTraceDto trace(String repoId, String workspaceId, String traceId) {
    List<TelemetrySpanDto> spans =
        store.trace(repoId, workspaceId, traceId).stream()
            .sorted(Comparator.comparingLong(StoredSpan::startEpochNanos))
            .map(TelemetrySpanDto::of)
            .toList();
    List<TelemetryLogDto> logs =
        store.logs(repoId, workspaceId).stream()
            .filter(log -> traceId.equals(log.traceId()))
            .sorted(Comparator.comparingLong(StoredLog::epochNanos))
            .map(TelemetryLogDto::of)
            .toList();
    return new TelemetryTraceDto(traceId, spans, logs);
  }

  /** How {@link #slowSpans} orders its result. */
  public enum SpanSort {
    /** Slowest first — the "what's slow" lens. */
    DURATION,
    /** Newest start time first — the "what did I just do" lens. */
    RECENT
  }

  /** Spans at least {@code thresholdMs} long, ordered per {@code sort}. */
  public List<TelemetrySpanDto> slowSpans(
      String repoId, String workspaceId, long thresholdMs, Integer sinceMinutes, SpanSort sort) {
    long cutoff = cutoff(sinceMinutes);
    Comparator<StoredSpan> order =
        sort == SpanSort.RECENT
            ? Comparator.comparingLong(StoredSpan::startEpochNanos).reversed()
            : Comparator.comparingLong(StoredSpan::durationMs).reversed();
    return store.spans(repoId, workspaceId).stream()
        .filter(span -> span.receivedAtMillis() >= cutoff && span.durationMs() >= thresholdMs)
        .sorted(order)
        .map(TelemetrySpanDto::of)
        .toList();
  }

  /**
   * Logs whose body or severity text contains {@code query} (case-insensitive), oldest first.
   * {@code service} additionally narrows to one service name (the UI's log-tail filter).
   */
  public List<TelemetryLogDto> searchLogs(
      String repoId, String workspaceId, String query, Integer sinceMinutes, String service) {
    long cutoff = cutoff(sinceMinutes);
    String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
    return store.logs(repoId, workspaceId).stream()
        .filter(log -> log.receivedAtMillis() >= cutoff)
        .filter(log -> service == null || service.isBlank() || service.equals(log.serviceName()))
        .filter(
            log ->
                needle.isEmpty()
                    || log.body().toLowerCase(Locale.ROOT).contains(needle)
                    || log.severityText().toLowerCase(Locale.ROOT).contains(needle))
        .sorted(Comparator.comparingLong(StoredLog::epochNanos))
        .map(TelemetryLogDto::of)
        .toList();
  }

  /** The latest point of every metric series, optionally narrowed to one metric name. */
  public List<TelemetryMetricDto> metrics(String repoId, String workspaceId, String name) {
    return store.metrics(repoId, workspaceId).stream()
        .filter(point -> name == null || name.isBlank() || name.equals(point.name()))
        .sorted(Comparator.comparing(point -> point.name()))
        .map(TelemetryMetricDto::of)
        .toList();
  }

  private static long cutoff(Integer sinceMinutes) {
    return sinceMinutes == null
        ? Long.MIN_VALUE
        : System.currentTimeMillis() - sinceMinutes * 60_000L;
  }
}
