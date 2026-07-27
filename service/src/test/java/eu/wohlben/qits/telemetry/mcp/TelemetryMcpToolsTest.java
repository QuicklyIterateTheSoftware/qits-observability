package eu.wohlben.qits.telemetry.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.telemetry.TelemetryFixtures;
import eu.wohlben.qits.telemetry.control.TelemetryDecoder;
import eu.wohlben.qits.telemetry.control.TelemetryStore;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the telemetry tools on the "observability" MCP server: they exist only for sessions
 * narrowed to repository + workspace, they answer from the session's workspace bucket only, and
 * error evidence is grouped by trace with correlated logs.
 *
 * <p>Scope setup goes through {@link FakeRepositoryScopeGuard} / {@link FakeWorkspaceLookup}
 * instead of the monorepo's real {@code POST /api/projects}, {@code .../repositories} and
 * {@code .../workspaces} calls: those endpoints, their databases and the {@code testing-repo.git}
 * fixture all belong to qits-projects and qits-workspaces. Every assertion below is unchanged.
 */
@QuarkusTest
public class TelemetryMcpToolsTest {

  @Inject TelemetryStore store;

  @Inject TelemetryDecoder decoder;

  @Inject FakeRepositoryScopeGuard scopeGuard;

  @Inject FakeWorkspaceLookup workspaces;

  @BeforeEach
  void resetStore() {
    store.clear();
    scopeGuard.reset();
    workspaces.reset();
  }

  /** Registers a repository as living in the session's project (the projects context's answer). */
  private String createRepository(String repoId) {
    scopeGuard.allow(repoId);
    return repoId;
  }

  /** Registers an active workspace of that repository (the workspaces context's answer). */
  private void createWorkspace(String repoId, String workspaceId) {
    workspaces.register(repoId, workspaceId);
  }

  /** Seeds the store through the real decoder — same records the receiver would produce. */
  private void seedErrorTrace(String repoId, String workspaceId, String traceId, String spanId) {
    store.addSpans(
        decoder.decodeSpans(
            TelemetryFixtures.errorTraceRequest("svc", repoId, workspaceId, traceId, spanId),
            System.currentTimeMillis()));
    store.addLogs(
        decoder.decodeLogs(
            TelemetryFixtures.logsRequest(
                "svc",
                repoId,
                workspaceId,
                SeverityNumber.SEVERITY_NUMBER_ERROR,
                "correlated error log",
                traceId),
            System.currentTimeMillis()));
  }

  private static String text(ToolResponse response) {
    return response.content().stream()
        .map(c -> c.asText().text())
        .collect(Collectors.joining("\n"));
  }

  private McpStreamableTestClient client(String repoId, String workspaceId) {
    return McpAssured.newStreamableClient()
        .setMcpPath("/observability/mcp")
        .setAdditionalHeaders(
            msg -> {
              io.vertx.core.MultiMap headers = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
              if (repoId != null) {
                headers.add(RepositoryScope.REPOSITORY_HEADER, repoId);
              }
              if (workspaceId != null) {
                headers.add(WorkspaceScope.WORKSPACE_HEADER, workspaceId);
              }
              return headers;
            })
        .build()
        .connect();
  }

  @Test
  public void errorsGroupByTraceAndTraceReturnsCorrelatedLogs() {
    String repoId = createRepository("repo-errors");
    createWorkspace(repoId, "work");
    seedErrorTrace(repoId, "work", TelemetryFixtures.TRACE_ID_A, TelemetryFixtures.SPAN_ID_A);
    seedErrorTrace(repoId, "work", TelemetryFixtures.TRACE_ID_B, TelemetryFixtures.SPAN_ID_B);
    var client = client(repoId, "work");

    client
        .when()
        .toolsCall(
            "telemetryErrors",
            Map.of(),
            r -> {
              String out = text(r);
              assertFalse(r.isError(), out);
              assertTrue(out.contains(TelemetryFixtures.TRACE_ID_A), out);
              assertTrue(out.contains(TelemetryFixtures.TRACE_ID_B), out);
              assertTrue(out.contains("java.lang.IllegalStateException"), out);
              assertTrue(out.contains("correlated error log"), out);
            })
        .toolsCall(
            "telemetryTrace",
            Map.of("traceId", TelemetryFixtures.TRACE_ID_A),
            r -> {
              String out = text(r);
              assertFalse(r.isError(), out);
              assertTrue(out.contains(TelemetryFixtures.SPAN_ID_A), out);
              assertTrue(out.contains("correlated error log"), out);
              assertFalse(out.contains(TelemetryFixtures.TRACE_ID_B), "other trace leaked: " + out);
            })
        .thenAssertResults();
  }

  @Test
  public void aSessionOnlySeesItsOwnWorkspacesTelemetry() {
    String repoId = createRepository("repo-isolation");
    createWorkspace(repoId, "mine");
    createWorkspace(repoId, "other");
    seedErrorTrace(repoId, "other", TelemetryFixtures.TRACE_ID_B, TelemetryFixtures.SPAN_ID_B);
    var client = client(repoId, "mine");

    client
        .when()
        .toolsCall(
            "telemetryErrors",
            Map.of(),
            r -> {
              String out = text(r);
              assertFalse(r.isError(), out);
              assertFalse(
                  out.contains(TelemetryFixtures.TRACE_ID_B),
                  "another workspace's telemetry leaked: " + out);
            })
        .thenAssertResults();
  }

  @Test
  public void slowSpansSearchLogsAndMetricsAnswerFromTheScopedBucket() {
    String repoId = createRepository("repo-queries");
    createWorkspace(repoId, "work");
    seedErrorTrace(repoId, "work", TelemetryFixtures.TRACE_ID_A, TelemetryFixtures.SPAN_ID_A);
    store.addMetrics(
        decoder.decodeMetrics(
            TelemetryFixtures.metricsRequest("svc", repoId, "work", 42.5, 7),
            System.currentTimeMillis()));
    var client = client(repoId, "work");

    client
        .when()
        .toolsCall(
            // The fixture span lasts 250ms, so a 100ms threshold catches it.
            "telemetrySlowSpans",
            Map.of("thresholdMs", 100),
            r -> assertTrue(text(r).contains("GET /boom"), text(r)))
        .toolsCall(
            "telemetrySearchLogs",
            Map.of("query", "CORRELATED"),
            r -> assertTrue(text(r).contains("correlated error log"), text(r)))
        .toolsCall(
            "telemetryMetrics",
            Map.of("name", "jvm.memory.used"),
            r -> {
              String out = text(r);
              assertTrue(out.contains("jvm.memory.used"), out);
              assertTrue(out.contains("42.5"), out);
              assertFalse(out.contains("http.server.requests"), "name filter ignored: " + out);
            })
        .thenAssertResults();
  }

  @Test
  public void telemetryToolsAreHiddenWithoutWorkspaceScope() {
    String repoId = createRepository("repo-filter");
    var repoOnly = client(repoId, null);
    repoOnly
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).collect(Collectors.toSet());
              // The monorepo also asserted listBranches is listed, as a sanity check that the
              // server itself answered. That tool is RepositoryMcpTools', i.e. qits-projects', and
              // is not in this repo; the positive half of this test (below) covers the same thing.
              assertFalse(names.contains("telemetryErrors"), "must be hidden: " + names);
            })
        .thenAssertResults();

    var workspaceScoped = client(repoId, "work");
    workspaceScoped
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).collect(Collectors.toSet());
              assertTrue(names.contains("telemetryErrors"), "must be listed: " + names);
            })
        .thenAssertResults();
  }
}
