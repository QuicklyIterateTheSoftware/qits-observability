package eu.wohlben.qits.domain.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Capture endpoint configured (QITS_CAPTURE_ENDPOINT under a supervising qits) but no OTLP
 * endpoint: the capture section relays verbatim while telemetry stays null — the two gates are
 * independent.
 */
@QuarkusTest
@TestProfile(ConfigResourceCaptureTest.CaptureOnlyProfile.class)
class ConfigResourceCaptureTest {

  public static class CaptureOnlyProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.capture.endpoint", "http://qits:8080/api/capture",
          "otel.resource.attributes", "qits.workspace.id=work,qits.repository.id=repo");
    }
  }

  @Test
  void captureRelaysEndpointAndIdentityWhileTelemetryStaysNull() {
    given()
        .when()
        .get("/api/config.json")
        .then()
        .statusCode(200)
        .body("capture.ingestUrl", equalTo("http://qits:8080/api/capture"))
        .body("capture.resourceAttributes.'qits.repository.id'", equalTo("repo"))
        .body("capture.resourceAttributes.'qits.workspace.id'", equalTo("work"))
        .body("telemetry", nullValue());
  }
}
