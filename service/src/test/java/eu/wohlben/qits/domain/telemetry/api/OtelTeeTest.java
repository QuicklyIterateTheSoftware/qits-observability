package eu.wohlben.qits.domain.telemetry.api;

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import eu.wohlben.qits.domain.telemetry.TelemetryFixtures;
import eu.wohlben.qits.domain.telemetry.control.TelemetryStore;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The managed case: with an upstream endpoint configured, every ingest still fills the local store
 * AND is forwarded byte-verbatim to the parent. The forward is async fire-and-forget, so upstream
 * assertions poll briefly.
 */
@QuarkusTest
@WithTestResource(OtelStubTestResource.class)
class OtelTeeTest {

  private static final String PROTOBUF = "application/x-protobuf";
  private static final String REPO = "repo-1";
  private static final String WORKSPACE = "wt-1";

  @Inject TelemetryStore store;

  @BeforeEach
  void reset() {
    store.clear();
    OtelStubTestResource.reset();
  }

  private static void awaitForward() {
    long deadline = System.currentTimeMillis() + 5_000;
    while (OtelStubTestResource.lastPath == null) {
      if (System.currentTimeMillis() > deadline) {
        fail("upstream stub never received the forwarded export");
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        fail("interrupted while waiting for the forward");
      }
    }
  }

  @Test
  void teesTraceExportToUpstreamAndStoresLocally() {
    byte[] body =
        TelemetryFixtures.okTraceRequest(
                "my-service",
                REPO,
                WORKSPACE,
                TelemetryFixtures.TRACE_ID_A,
                TelemetryFixtures.SPAN_ID_A)
            .toByteArray();

    given()
        .contentType(PROTOBUF)
        .body(body)
        .when()
        .post("/api/otel/v1/traces")
        .then()
        .statusCode(200);

    assertEquals(1, store.spans(REPO, WORKSPACE).size(), "local store must still fill");
    awaitForward();
    assertEquals("POST", OtelStubTestResource.lastMethod);
    assertEquals("/v1/traces", OtelStubTestResource.lastPath);
    assertArrayEquals(body, OtelStubTestResource.lastBody, "forward must be byte-verbatim");
  }

  /** Upstream rejection (the stub 400s /v1/logs) must not affect the local ingest. */
  @Test
  void upstreamRejectionDoesNotFailLocalIngest() {
    byte[] body =
        TelemetryFixtures.logsRequest(
                "my-service",
                REPO,
                WORKSPACE,
                SeverityNumber.SEVERITY_NUMBER_ERROR,
                "it broke",
                TelemetryFixtures.TRACE_ID_A)
            .toByteArray();

    given()
        .contentType(PROTOBUF)
        .body(body)
        .when()
        .post("/api/otel/v1/logs")
        .then()
        .statusCode(200);

    assertEquals(1, store.logs(REPO, WORKSPACE).size());
    awaitForward();
    assertEquals("/v1/logs", OtelStubTestResource.lastPath);
    assertArrayEquals(body, OtelStubTestResource.lastBody);
  }

  /** Gzipped bodies forward still-gzipped, with Content-Encoding relayed. */
  @Test
  void forwardsGzippedBodyUndecompressed() {
    byte[] gzipped =
        TelemetryFixtures.gzip(
            TelemetryFixtures.okTraceRequest(
                    "my-service",
                    REPO,
                    WORKSPACE,
                    TelemetryFixtures.TRACE_ID_B,
                    TelemetryFixtures.SPAN_ID_B)
                .toByteArray());

    // Bare content type: rest-assured otherwise appends a charset the real exporter never sends.
    given()
        .config(
            io.restassured.RestAssured.config()
                .encoderConfig(
                    encoderConfig().appendDefaultContentCharsetToContentTypeIfUndefined(false)))
        .contentType(PROTOBUF)
        .header("Content-Encoding", "gzip")
        .body(gzipped)
        .when()
        .post("/api/otel/v1/traces")
        .then()
        .statusCode(200);

    assertEquals(1, store.trace(REPO, WORKSPACE, TelemetryFixtures.TRACE_ID_B).size());
    awaitForward();
    assertArrayEquals(gzipped, OtelStubTestResource.lastBody);
    assertEquals("gzip", OtelStubTestResource.lastContentEncoding);
    assertEquals(PROTOBUF, OtelStubTestResource.lastContentType);
  }
}
