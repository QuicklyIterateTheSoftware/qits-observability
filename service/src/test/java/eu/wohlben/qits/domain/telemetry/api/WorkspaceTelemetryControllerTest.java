package eu.wohlben.qits.domain.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;

import eu.wohlben.qits.domain.telemetry.TelemetryFixtures;
import eu.wohlben.qits.domain.telemetry.control.TelemetryDecoder;
import eu.wohlben.qits.domain.telemetry.control.TelemetryStore;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The UI's JSON twins answer from the same store buckets as the MCP tools. */
@QuarkusTest
class WorkspaceTelemetryControllerTest {

  private static final String REPO = "repo-rest";
  private static final String WORKSPACE = "wt-rest";
  private static final String BASE =
      "/api/repositories/" + REPO + "/workspaces/" + WORKSPACE + "/telemetry";

  @Inject TelemetryStore store;

  @Inject TelemetryDecoder decoder;

  @BeforeEach
  void seed() {
    store.clear();
    long now = System.currentTimeMillis();
    store.addSpans(
        decoder.decodeSpans(
            TelemetryFixtures.errorTraceRequest(
                "svc", REPO, WORKSPACE, TelemetryFixtures.TRACE_ID_A, TelemetryFixtures.SPAN_ID_A),
            now));
    store.addLogs(
        decoder.decodeLogs(
            TelemetryFixtures.logsRequest(
                "svc",
                REPO,
                WORKSPACE,
                SeverityNumber.SEVERITY_NUMBER_ERROR,
                "rest error log",
                TelemetryFixtures.TRACE_ID_A),
            now));
    store.addMetrics(
        decoder.decodeMetrics(
            TelemetryFixtures.metricsRequest("svc", REPO, WORKSPACE, 1.5, 3), now));
  }

  @Test
  void errorsGroupsByTrace() {
    given()
        .get(BASE + "/errors")
        .then()
        .statusCode(200)
        .body("groups", hasSize(1))
        .body("groups[0].traceId", equalTo(TelemetryFixtures.TRACE_ID_A))
        .body("groups[0].errorSpans", hasSize(1))
        .body("groups[0].errorLogs[0].body", equalTo("rest error log"));
  }

  @Test
  void traceReturnsSpansAndCorrelatedLogs() {
    given()
        .get(BASE + "/traces/" + TelemetryFixtures.TRACE_ID_A)
        .then()
        .statusCode(200)
        .body("trace.spans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_A))
        .body("trace.logs[0].body", equalTo("rest error log"));
  }

  @Test
  void slowSpansRespectsThreshold() {
    // The fixture span lasts 250ms.
    given()
        .get(BASE + "/slow-spans?thresholdMs=100")
        .then()
        .statusCode(200)
        .body("spans", hasSize(1))
        .body("spans[0].durationMs", greaterThanOrEqualTo(250));
    given()
        .get(BASE + "/slow-spans?thresholdMs=10000")
        .then()
        .statusCode(200)
        .body("spans", hasSize(0));
  }

  @Test
  void slowSpansSortRecentOrdersByStartTimeDesc() {
    // A later-starting but faster span: with the default duration sort it comes second,
    // with sort=recent it comes first.
    store.addSpans(
        decoder.decodeSpans(
            TelemetryFixtures.traceRequest(
                TelemetryFixtures.resource("svc", REPO, WORKSPACE),
                TelemetryFixtures.spanBuilder(
                        TelemetryFixtures.TRACE_ID_B,
                        TelemetryFixtures.SPAN_ID_B,
                        "GET /later",
                        2_000_000_000L,
                        2_100_000_000L)
                    .build()),
            System.currentTimeMillis()));
    given()
        .get(BASE + "/slow-spans?thresholdMs=0")
        .then()
        .statusCode(200)
        .body("spans", hasSize(2))
        .body("spans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_A));
    given()
        .get(BASE + "/slow-spans?thresholdMs=0&sort=recent")
        .then()
        .statusCode(200)
        .body("spans", hasSize(2))
        .body("spans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_B));
  }

  @Test
  void logsFilterByQueryAndService() {
    given()
        .get(BASE + "/logs?query=REST")
        .then()
        .statusCode(200)
        .body("logs", hasSize(1))
        .body("logs[0].serviceName", equalTo("svc"));
    given().get(BASE + "/logs?service=unknown-svc").then().statusCode(200).body("logs", hasSize(0));
  }

  @Test
  void metricsReturnLatestPerSeriesWithNameFilter() {
    given().get(BASE + "/metrics").then().statusCode(200).body("metrics", hasSize(2));
    given()
        .get(BASE + "/metrics?name=jvm.memory.used")
        .then()
        .statusCode(200)
        .body("metrics", hasSize(1))
        .body("metrics[0].value", equalTo(1.5f));
  }

  @Test
  void anotherWorkspaceSeesNothing() {
    given()
        .get("/api/repositories/" + REPO + "/workspaces/elsewhere/telemetry/errors")
        .then()
        .statusCode(200)
        .body("groups", hasSize(0));
  }
}
