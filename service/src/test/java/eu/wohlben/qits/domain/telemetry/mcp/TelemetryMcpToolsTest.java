package eu.wohlben.qits.domain.telemetry.mcp;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.domain.project.api.ProjectController;
import eu.wohlben.qits.domain.repository.mcp.ProjectScope;
import eu.wohlben.qits.domain.telemetry.TelemetryFixtures;
import eu.wohlben.qits.domain.telemetry.control.TelemetryDecoder;
import eu.wohlben.qits.domain.telemetry.control.TelemetryStore;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the telemetry tools on the "repository" MCP server: they exist only for sessions
 * narrowed to repository + workspace, they answer from the session's workspace bucket only, and
 * error evidence is grouped by trace with correlated logs.
 */
@QuarkusTest
@TestProfile(TelemetryMcpToolsTest.TestProfile.class)
public class TelemetryMcpToolsTest {

  public static class TestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      try {
        Path tempDir = Files.createTempDirectory("qits-telemetry-mcp-test-repos");
        return Map.of("qits.repositories.data-dir", tempDir.toString());
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Inject TelemetryStore store;

  @Inject TelemetryDecoder decoder;

  private final String fixtureUrl;

  public TelemetryMcpToolsTest() throws Exception {
    fixtureUrl = getClass().getResource("/fixtures/testing-repo.git").toURI().getPath();
  }

  @BeforeEach
  void resetStore() {
    store.clear();
  }

  private String createProject(String name) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRequest(name, null))
        .post("/api/projects")
        .then()
        .statusCode(200)
        .extract()
        .path("project.id");
  }

  private String createRepository(String projectId) {
    return given()
        .contentType(ContentType.JSON)
        .body(new ProjectController.CreateProjectRepositoryRequest(fixtureUrl, null, null))
        .post("/api/projects/" + projectId + "/repositories")
        .then()
        .statusCode(200)
        .extract()
        .path("repository.id");
  }

  private void createWorkspace(String repoId, String workspaceId) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("id", workspaceId, "parent", "master"))
        .post("/api/repositories/" + repoId + "/workspaces")
        .then()
        .statusCode(200);
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

  private McpStreamableTestClient client(String projectId, String repoId, String workspaceId) {
    return McpAssured.newStreamableClient()
        .setMcpPath("/mcp/repository")
        .setAdditionalHeaders(
            msg -> {
              io.vertx.core.MultiMap headers = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
              headers.add(ProjectScope.PROJECT_HEADER, projectId);
              if (repoId != null) {
                headers.add(ProjectScope.REPOSITORY_HEADER, repoId);
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
    String project = createProject("Telemetry");
    String repoId = createRepository(project);
    createWorkspace(repoId, "work");
    seedErrorTrace(repoId, "work", TelemetryFixtures.TRACE_ID_A, TelemetryFixtures.SPAN_ID_A);
    seedErrorTrace(repoId, "work", TelemetryFixtures.TRACE_ID_B, TelemetryFixtures.SPAN_ID_B);
    var client = client(project, repoId, "work");

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
    String project = createProject("Telemetry Isolation");
    String repoId = createRepository(project);
    createWorkspace(repoId, "mine");
    createWorkspace(repoId, "other");
    seedErrorTrace(repoId, "other", TelemetryFixtures.TRACE_ID_B, TelemetryFixtures.SPAN_ID_B);
    var client = client(project, repoId, "mine");

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
    String project = createProject("Telemetry Queries");
    String repoId = createRepository(project);
    createWorkspace(repoId, "work");
    seedErrorTrace(repoId, "work", TelemetryFixtures.TRACE_ID_A, TelemetryFixtures.SPAN_ID_A);
    store.addMetrics(
        decoder.decodeMetrics(
            TelemetryFixtures.metricsRequest("svc", repoId, "work", 42.5, 7),
            System.currentTimeMillis()));
    var client = client(project, repoId, "work");

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
    String project = createProject("Telemetry Filter");
    String repoId = createRepository(project);
    var repoOnly = client(project, repoId, null);
    repoOnly
        .when()
        .toolsList(
            page -> {
              var names = page.tools().stream().map(t -> t.name()).collect(Collectors.toSet());
              assertTrue(names.contains("listBranches"), "sanity: " + names);
              assertFalse(names.contains("telemetryErrors"), "must be hidden: " + names);
            })
        .thenAssertResults();

    var workspaceScoped = client(project, repoId, "work");
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
