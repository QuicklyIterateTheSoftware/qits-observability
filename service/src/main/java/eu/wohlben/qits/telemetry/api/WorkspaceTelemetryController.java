package eu.wohlben.qits.telemetry.api;

import eu.wohlben.qits.telemetry.control.TelemetryQueryService;
import eu.wohlben.qits.telemetry.dto.TelemetryErrorGroupDto;
import eu.wohlben.qits.telemetry.dto.TelemetryLogDto;
import eu.wohlben.qits.telemetry.dto.TelemetryMetricDto;
import eu.wohlben.qits.telemetry.dto.TelemetrySourceDto;
import eu.wohlben.qits.telemetry.dto.TelemetrySpanDto;
import eu.wohlben.qits.telemetry.dto.TelemetryStoreStateDto;
import eu.wohlben.qits.telemetry.dto.TelemetryTraceDto;
import eu.wohlben.qits.telemetry.dto.TelemetryTraceSummaryDto;
import eu.wohlben.qits.telemetry.error.BadRequestException;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The REST twins of the telemetry MCP tools, plus the listing queries the tools deliberately do not
 * have. Read-only JSON over the same {@link TelemetryQueryService}, so humans and agents see
 * identical answers to the questions both are allowed to ask.
 *
 * <p><strong>Naming a bucket.</strong> Two ways, mutually exclusive, {@code ?source=} winning:
 *
 * <ul>
 *   <li>{@code ?source=<key>} — a key handed out by {@link #sources()}, passed back verbatim. The
 *       key is opaque; do not construct one. This is the only way to reach the buckets keyed on
 *       {@code service.name}, which is where every platform process's telemetry lands.
 *   <li>{@code ?repositoryId=&workspaceId=} — the original workspace lens, unchanged.
 * </ul>
 *
 * <p>The repository and the workspace are <em>scope</em>, not containment: this context owns
 * neither, and buckets telemetry by the string ids an exporter stamped into its resource
 * attributes. So they arrive as filters rather than as path segments of another context's
 * aggregate — {@code traceId} stays in the path because it is the identity of the thing being
 * fetched.
 *
 * <p><strong>An unknown bucket is empty, not a 404.</strong> An unknown source key, an unknown
 * workspace pair and a bucket the eviction emptied all answer 200 with nothing in it, because the
 * store cannot tell them apart and neither should this. What makes them distinguishable is {@link
 * #sources()} and {@link #store()}: whether the key is listed, and what the buffer has dropped. A
 * screen that reads those two can name its empty state; one that guesses from an empty list cannot.
 *
 * <p><strong>Everything that lists is bounded.</strong> {@code ?limit=} defaults to {@value
 * #DEFAULT_LIMIT} and is refused above {@value #MAX_LIMIT} rather than quietly clamped — a caller
 * that asked for more than it can have should hear so. The envelopes carry {@code total} and {@code
 * truncated} beside the items so a screen can say "showing 200 of 1,841".
 *
 * <p>Nothing here is an authorization boundary: an unknown or foreign scope selects a bucket that
 * is simply empty. The scoping ports ({@code RepositoryScopeGuard} / {@code WorkspaceLookup}) guard
 * the MCP surface, where the scope comes from the agent's connection rather than from the caller.
 */
@Path("/telemetry")
@Produces(MediaType.APPLICATION_JSON)
@jakarta.annotation.security.RolesAllowed("qits:admin")
public class WorkspaceTelemetryController {

  /** What a list answers with when the caller does not choose. */
  static final int DEFAULT_LIMIT = 200;

  /** The most any list will answer with. Above this is a 400, not a silent trim. */
  static final int MAX_LIMIT = 1000;

  @Inject TelemetryQueryService queryService;

  /**
   * The buffer's own state. Everything a screen needs to say what it is showing: when the buffer
   * started, what it caps at, how many sources it holds and what it has thrown away.
   */
  @GET
  @Path("/store")
  public TelemetryStoreStateDto store() {
    return queryService.storeState();
  }

  public static record ListTelemetrySourcesRequest() {
    @Schema(name = "ListTelemetrySourcesResponse")
    public record Response(List<TelemetrySourceDto> sources) {}
  }

  /** Every bucket in the buffer. The {@code key} of each is what {@code ?source=} takes. */
  @GET
  @Path("/sources")
  public ListTelemetrySourcesRequest.Response sources() {
    return new ListTelemetrySourcesRequest.Response(queryService.sources());
  }

  public static record ListTelemetryErrorsRequest() {
    @Schema(name = "ListTelemetryErrorsResponse")
    public record Response(List<TelemetryErrorGroupDto> groups, int total, boolean truncated) {}
  }

  @GET
  @Path("/errors")
  public ListTelemetryErrorsRequest.Response errors(
      @QueryParam("source") String source,
      @QueryParam("repositoryId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @QueryParam("service") String service,
      @QueryParam("sinceMinutes") Integer sinceMinutes,
      @QueryParam("limit") @DefaultValue("" + DEFAULT_LIMIT) int limit) {
    TelemetryQueryService.Page<TelemetryErrorGroupDto> page =
        queryService.errorsIn(
            TelemetryQueryService.sourceKey(source, repoId, workspaceId),
            service,
            sinceMinutes,
            checkedLimit(limit));
    return new ListTelemetryErrorsRequest.Response(page.items(), page.total(), page.truncated());
  }

  public static record ListTelemetryTracesRequest() {
    @Schema(name = "ListTelemetryTracesResponse")
    public record Response(List<TelemetryTraceSummaryDto> traces, int total, boolean truncated) {}
  }

  /**
   * The trace list: one row per buffered trace, newest-first by default. {@code sort=duration}
   * flips it to the slowest-first lens; anything else means {@code recent}, matching {@code
   * slow-spans}' long-standing coercion rather than inventing a second spelling.
   */
  @GET
  @Path("/traces")
  public ListTelemetryTracesRequest.Response traces(
      @QueryParam("source") String source,
      @QueryParam("repositoryId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @QueryParam("service") String service,
      @QueryParam("thresholdMs") @DefaultValue("0") long thresholdMs,
      @QueryParam("sinceMinutes") Integer sinceMinutes,
      @QueryParam("sort") @DefaultValue("recent") String sort,
      @QueryParam("limit") @DefaultValue("" + DEFAULT_LIMIT) int limit) {
    TelemetryQueryService.Page<TelemetryTraceSummaryDto> page =
        queryService.tracesIn(
            TelemetryQueryService.sourceKey(source, repoId, workspaceId),
            service,
            thresholdMs,
            sinceMinutes,
            "duration".equalsIgnoreCase(sort)
                ? TelemetryQueryService.SpanSort.DURATION
                : TelemetryQueryService.SpanSort.RECENT,
            checkedLimit(limit));
    return new ListTelemetryTracesRequest.Response(page.items(), page.total(), page.truncated());
  }

  public static record GetTelemetryTraceRequest() {
    @Schema(name = "GetTelemetryTraceResponse")
    public record Response(TelemetryTraceDto trace) {}
  }

  /**
   * One trace's spans and correlated logs. An id that was never seen and one whose spans were
   * evicted both answer an empty trace — read {@code store.evictedSpans} to tell a screen which
   * halves of that sentence it is allowed to say.
   */
  @GET
  @Path("/traces/{traceId}")
  public GetTelemetryTraceRequest.Response trace(
      @QueryParam("source") String source,
      @QueryParam("repositoryId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @PathParam("traceId") String traceId) {
    return new GetTelemetryTraceRequest.Response(
        queryService.traceIn(
            TelemetryQueryService.sourceKey(source, repoId, workspaceId), traceId));
  }

  public static record ListSlowSpansRequest() {
    @Schema(name = "ListSlowSpansResponse")
    public record Response(List<TelemetrySpanDto> spans, int total, boolean truncated) {}
  }

  @GET
  @Path("/slow-spans")
  public ListSlowSpansRequest.Response slowSpans(
      @QueryParam("source") String source,
      @QueryParam("repositoryId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @QueryParam("service") String service,
      @QueryParam("thresholdMs") @DefaultValue("500") long thresholdMs,
      @QueryParam("sinceMinutes") Integer sinceMinutes,
      @QueryParam("sort") @DefaultValue("duration") String sort,
      @QueryParam("limit") @DefaultValue("" + DEFAULT_LIMIT) int limit) {
    TelemetryQueryService.SpanSort spanSort =
        "recent".equalsIgnoreCase(sort)
            ? TelemetryQueryService.SpanSort.RECENT
            : TelemetryQueryService.SpanSort.DURATION;
    TelemetryQueryService.Page<TelemetrySpanDto> page =
        queryService.slowSpansIn(
            TelemetryQueryService.sourceKey(source, repoId, workspaceId),
            service,
            thresholdMs,
            sinceMinutes,
            spanSort,
            checkedLimit(limit));
    return new ListSlowSpansRequest.Response(page.items(), page.total(), page.truncated());
  }

  public static record SearchTelemetryLogsRequest() {
    @Schema(name = "SearchTelemetryLogsResponse")
    public record Response(List<TelemetryLogDto> logs, int total, boolean truncated) {}
  }

  /**
   * The log tail, oldest-first. {@code query} matches the body <em>and</em> the severity text,
   * case-insensitively — searching "error" finds ERROR-severity records, which surprises anyone who
   * was not told. When the answer is bounded it keeps the newest matches: a tail wants the end.
   *
   * <p>{@code minSeverity} is the severity band, by name ({@code TRACE}…{@code FATAL}) or by a raw
   * OTel number, and it is a <em>floor</em>: {@code WARN} answers warnings and worse. It is a
   * parameter rather than something a screen does to the answer because this endpoint truncates —
   * filtering a page already cut to 200 would show "the errors among the last 200 records" while
   * reading as "the last 200 errors". An unrecognised value is a 400, because a severity filter
   * that silently stopped filtering is the one wrong answer this endpoint must never give. Records
   * carrying no severity at all are excluded whenever a floor is named.
   */
  @GET
  @Path("/logs")
  public SearchTelemetryLogsRequest.Response logs(
      @QueryParam("source") String source,
      @QueryParam("repositoryId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @QueryParam("query") String query,
      @QueryParam("service") String service,
      @QueryParam("minSeverity") String minSeverity,
      @QueryParam("sinceMinutes") Integer sinceMinutes,
      @QueryParam("limit") @DefaultValue("" + DEFAULT_LIMIT) int limit) {
    TelemetryQueryService.Page<TelemetryLogDto> page =
        queryService.searchLogsIn(
            TelemetryQueryService.sourceKey(source, repoId, workspaceId),
            query,
            sinceMinutes,
            service,
            TelemetryQueryService.severityFloor(minSeverity),
            checkedLimit(limit));
    return new SearchTelemetryLogsRequest.Response(page.items(), page.total(), page.truncated());
  }

  public static record ListTelemetryMetricsRequest() {
    @Schema(name = "ListTelemetryMetricsResponse")
    public record Response(List<TelemetryMetricDto> metrics) {}
  }

  /**
   * The latest point of every metric series. No {@code limit}: the store keeps one point per series
   * and caps the series count, so this answer is already bounded — and there is no history to page
   * through, because the store replaces each point in place rather than accumulating one.
   */
  @GET
  @Path("/metrics")
  public ListTelemetryMetricsRequest.Response metrics(
      @QueryParam("source") String source,
      @QueryParam("repositoryId") String repoId,
      @QueryParam("workspaceId") String workspaceId,
      @QueryParam("service") String service,
      @QueryParam("name") String name) {
    return new ListTelemetryMetricsRequest.Response(
        queryService.metricsIn(
            TelemetryQueryService.sourceKey(source, repoId, workspaceId), name, service));
  }

  private static int checkedLimit(int limit) {
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
    }
    return limit;
  }
}
