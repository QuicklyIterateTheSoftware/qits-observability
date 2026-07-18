package eu.wohlben.qits.domain.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The unconfigured (standalone, not-managed) case: both relay sections report dark. */
@QuarkusTest
class ConfigResourceTest {

  @Test
  void configReportsNullTelemetryWithoutOtelEndpoint() {
    given().when().get("/api/config.json").then().statusCode(200).body("telemetry", nullValue());
  }

  @Test
  void configReportsNullCaptureWithoutCaptureEndpoint() {
    given().when().get("/api/config.json").then().statusCode(200).body("capture", nullValue());
  }
}
