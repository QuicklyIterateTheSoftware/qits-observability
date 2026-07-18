package eu.wohlben.qits.domain.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/** The managed case: config.json relays the injected OTEL identity, parsed into attributes. */
@QuarkusTest
@WithTestResource(OtelStubTestResource.class)
class ConfigResourceRelayTest {

  @Test
  void configRelaysParsedIdentity() {
    given()
        .when()
        .get("/api/config.json")
        .then()
        .statusCode(200)
        .body("telemetry.serviceName", is("qits-dev"))
        .body("telemetry.resourceAttributes.'qits.workspace.id'", is("ws-1"))
        .body("telemetry.resourceAttributes.'qits.repository.id'", is("repo-1"))
        .body("telemetry.resourceAttributes.'qits.command.id'", is("cmd-1"));
  }
}
