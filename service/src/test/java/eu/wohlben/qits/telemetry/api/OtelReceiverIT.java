package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

import eu.wohlben.qits.telemetry.TelemetryFixtures;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

/**
 * Ingest against the <em>packaged</em> process — the fast-jar under {@code -DskipITs=false}, the
 * GraalVM binary under {@code -Dnative}.
 *
 * <p>This exists because of native-image, and specifically because of protobuf. {@link
 * eu.wohlben.qits.telemetry.control.TelemetryDecoder} and {@link OtelReceiverResource} are the only
 * things here that touch {@code io.opentelemetry.proto.*}, and generated protobuf messages resolve
 * descriptors, builders and field accessors reflectively — the classic shape of a dependency that is
 * green all through {@code OtelReceiverResourceTest} on the JVM and throws at the first export in
 * the image. A boot check would not catch it: nothing loads a message class until a body arrives.
 *
 * <p>So every assertion here reads the export back out through the REST query surface rather than
 * stopping at the 200. A receiver that accepted the bytes and decoded nothing would answer 200 all
 * day; only the round trip proves the wire format survived compilation. {@code TelemetryStore} is in
 * the other process, so there is no injecting it and no {@code clear()} — each test carries its own
 * repository/workspace scope instead, which is exactly how the buckets are keyed anyway.
 */
@QuarkusIntegrationTest
class OtelReceiverIT {

  private static final String PROTOBUF = "application/x-protobuf";
  private static final String INGEST = "/observability/api/otel/v1";
  private static final String QUERY = "/observability/api/telemetry";

  @Test
  void traceExportDecodesIntoTheQueryableStore() {
    String repo = "it-traces";
    String workspace = "wt-traces";

    given()
        .contentType(PROTOBUF)
        .body(
            TelemetryFixtures.errorTraceRequest(
                    "it-service",
                    repo,
                    workspace,
                    TelemetryFixtures.TRACE_ID_A,
                    TelemetryFixtures.SPAN_ID_A)
                .toByteArray())
        .when()
        .post(INGEST + "/traces")
        .then()
        .statusCode(200)
        .contentType(PROTOBUF);

    // The span, its status, its resource attributes and its nested exception event all came out of
    // the protobuf — hex-encoded ids included, which is ByteString round-tripping through the image.
    given()
        .get(QUERY + "/errors?repositoryId=" + repo + "&workspaceId=" + workspace)
        .then()
        .statusCode(200)
        .body("groups", hasSize(1))
        .body("groups[0].traceId", equalTo(TelemetryFixtures.TRACE_ID_A))
        .body("groups[0].serviceName", equalTo("it-service"))
        .body("groups[0].errorSpans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_A))
        .body("groups[0].errorSpans[0].name", equalTo("GET /boom"))
        .body(
            "groups[0].errorSpans[0].events[0].attributes.'exception.type'",
            equalTo("java.lang.IllegalStateException"));
  }

  @Test
  void logExportDecodesIntoTheQueryableStore() {
    String repo = "it-logs";
    String workspace = "wt-logs";

    given()
        .contentType(PROTOBUF)
        .body(
            TelemetryFixtures.logsRequest(
                    "it-service",
                    repo,
                    workspace,
                    SeverityNumber.SEVERITY_NUMBER_ERROR,
                    "it broke in the binary",
                    TelemetryFixtures.TRACE_ID_A)
                .toByteArray())
        .when()
        .post(INGEST + "/logs")
        .then()
        .statusCode(200);

    given()
        .get(QUERY + "/logs?repositoryId=" + repo + "&workspaceId=" + workspace)
        .then()
        .statusCode(200)
        .body("logs", hasSize(1))
        .body("logs[0].body", equalTo("it broke in the binary"))
        .body("logs[0].severityText", equalTo("ERROR"))
        .body("logs[0].traceId", equalTo(TelemetryFixtures.TRACE_ID_A));
  }

  @Test
  void metricExportDecodesBothGaugeAndSum() {
    String repo = "it-metrics";
    String workspace = "wt-metrics";

    given()
        .contentType(PROTOBUF)
        .body(TelemetryFixtures.metricsRequest("it-service", repo, workspace, 12.5, 300).toByteArray())
        .when()
        .post(INGEST + "/metrics")
        .then()
        .statusCode(200);

    // The oneof discriminators (Gauge vs Sum, asDouble vs asInt) are read through the generated
    // has*/get* accessors, so a wrong answer here means the message classes decoded partially.
    given()
        .get(QUERY + "/metrics?repositoryId=" + repo + "&workspaceId=" + workspace)
        .then()
        .statusCode(200)
        .body("metrics", hasSize(2))
        .body("metrics.name", hasItem("jvm.memory.used"))
        .body("metrics.find { it.name == 'jvm.memory.used' }.value", equalTo(12.5f))
        .body("metrics.find { it.name == 'jvm.memory.used' }.type", equalTo("GAUGE"))
        .body("metrics.find { it.name == 'http.server.requests' }.value", equalTo(300.0f))
        .body("metrics.find { it.name == 'http.server.requests' }.type", equalTo("COUNTER"));
  }

  @Test
  void gzippedExportIsDetectedByMagicBytes() {
    String repo = "it-gzip";
    String workspace = "wt-gzip";

    given()
        .contentType(PROTOBUF)
        .body(
            TelemetryFixtures.gzip(
                TelemetryFixtures.okTraceRequest(
                        "it-service",
                        repo,
                        workspace,
                        TelemetryFixtures.TRACE_ID_B,
                        TelemetryFixtures.SPAN_ID_B)
                    .toByteArray()))
        .when()
        .post(INGEST + "/traces")
        .then()
        .statusCode(200);

    given()
        .get(
            QUERY
                + "/traces/"
                + TelemetryFixtures.TRACE_ID_B
                + "?repositoryId="
                + repo
                + "&workspaceId="
                + workspace)
        .then()
        .statusCode(200)
        .body("trace.spans[0].spanId", equalTo(TelemetryFixtures.SPAN_ID_B));
  }

  @Test
  void malformedProtobufIsA400AndNotA500() {
    given()
        .contentType(PROTOBUF)
        .body(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x13})
        .when()
        .post(INGEST + "/traces")
        .then()
        .statusCode(400);
  }

  /**
   * The MCP server refuses to start without {@code quarkus.mcp.server.observability.http.root-path},
   * so this route answering at all is what says the extension came up in the packaged process rather
   * than the request falling through to the router's 404.
   */
  @Test
  void mcpEndpointIsMountedUnderTheObservabilitySegment() {
    given()
        .contentType("application/json")
        .accept("application/json, text/event-stream")
        .body(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}")
        .when()
        .post("/observability/mcp")
        .then()
        .statusCode(200);
  }

  /** The framework's own surface moved with {@code quarkus.http.non-application-root-path}. */
  @Test
  void openApiAndSwaggerUiAreServedUnderTheObservabilitySegment() {
    given().get("/observability/q/openapi").then().statusCode(200);
    given().get("/observability/q/swagger-ui/").then().statusCode(200);
  }
}
