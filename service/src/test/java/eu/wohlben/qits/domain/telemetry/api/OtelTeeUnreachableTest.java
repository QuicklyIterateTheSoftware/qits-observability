package eu.wohlben.qits.domain.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.domain.telemetry.TelemetryFixtures;
import eu.wohlben.qits.domain.telemetry.control.TelemetryStore;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An unreachable parent must be invisible to the local ingest: the forward is fire-and-forget, so
 * the receiver still answers 200 and the store still fills (telemetry is best-effort upstream).
 */
@QuarkusTest
@TestProfile(OtelTeeUnreachableTest.UnreachableUpstreamProfile.class)
class OtelTeeUnreachableTest {

  public static class UnreachableUpstreamProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      // Port 1 is never listening; connect fails fast.
      return Map.of("otel.exporter.otlp.endpoint", "http://localhost:1");
    }
  }

  @Inject TelemetryStore store;

  @Test
  void localIngestSurvivesUnreachableUpstream() {
    store.clear();
    byte[] body =
        TelemetryFixtures.okTraceRequest(
                "my-service",
                "repo-1",
                "wt-1",
                TelemetryFixtures.TRACE_ID_A,
                TelemetryFixtures.SPAN_ID_A)
            .toByteArray();

    given()
        .contentType("application/x-protobuf")
        .body(body)
        .when()
        .post("/api/otel/v1/traces")
        .then()
        .statusCode(200);

    assertEquals(1, store.spans("repo-1", "wt-1").size());
  }
}
