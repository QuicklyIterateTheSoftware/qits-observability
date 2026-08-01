package eu.wohlben.qits.telemetry.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

/**
 * The surface of the <em>packaged artifact</em> — the fast-jar under {@code -DskipITs=false}, the
 * GraalVM binary under {@code -Dnative} — because that is the only place a whole class of failure
 * is visible.
 *
 * <p>Every other test here is a {@code @QuarkusTest}: it runs in the build JVM with <b>Quinoa
 * disabled</b> — the extension is off by default in test mode, so no {@code @QuarkusTest} in this
 * repo has ever seen the client at all. What the SPA is actually served as is proven here or
 * nowhere. {@link OtelReceiverIT} covers the ingest half of the artifact; this covers the serving
 * half, matching the qits-ci / qits-cd / qits-projects / qits-events precedent.
 *
 * <p>The probe list is the platform's, from {@code docs/project-setup-quinoa-angular.md}:
 *
 * <ul>
 *   <li>{@code /observability/} → 200 HTML carrying the right {@code <base href>} — the client's
 *       own spelling of the segment, set in another repository's {@code angular.json}, where no
 *       build here can check it. Wrong, and the page loads and then fetches its JavaScript from
 *       nowhere.
 *   <li>a deep link → 200 {@code index.html}, so the Angular router owns it across a reload
 *   <li>{@code /observability/api/<real>} → the API's own answer; {@code /observability/api/nope}
 *       → 404 and <b>never</b> the client. A machine client parses {@code index.html} as data, so
 *       the absence of the client is as much of the assertion as the status.
 *   <li>the readiness endpoint qits-cd's health gate curls, at the address the deployment assumes
 *   <li>{@code /mcp}: the third entry in {@code quarkus.quinoa.ignored-path-prefixes} — a mistyped
 *       path under it must reach the machine surface, not the SPA fallback.
 * </ul>
 *
 * <p>ITs are skipped by default ({@code skipITs} in the root pom) because they need a `package`,
 * and a package here needs the webui submodule and a node on PATH — neither of which the
 * clone-alone rule promises. Ask for them explicitly.
 */
@QuarkusIntegrationTest
class PackagedSurfaceIT {

  private static final String CLIENT_MARK = "<base href=\"/observability/\">";

  @Test
  void theClientIsServedAtTheSegmentWithItsOwnBaseHref() {
    String html =
        given()
            .when()
            .get("/observability/")
            .then()
            .statusCode(200)
            .contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(
        html.contains(CLIENT_MARK),
        "the client's baseHref must be the segment it is mounted at; got: "
            + html.substring(0, Math.min(400, html.length())));
  }

  @Test
  void aDeepLinkFallsBackToTheClientSoItsRouterOwnsIt() {
    // /observability/traces/abc is a route only the Angular router knows; across a reload only
    // enable-spa-routing keeps it alive.
    String deepLink =
        given()
            .when()
            .get("/observability/traces/abc")
            .then()
            .statusCode(200)
            .contentType(ContentType.HTML)
            .extract()
            .asString();
    assertTrue(
        deepLink.contains(CLIENT_MARK),
        "a deep link must answer with index.html, not with a differently-shaped page");
  }

  @Test
  void theBareSegmentRedirectsRatherThanFourOhFouring() {
    // Quinoa mounts at /observability/*, which does not match the bare segment (upstream #960) —
    // the redirect in webui/WebUiRedirect is this service's answer, and it only exists in the
    // packaged process alongside a real client.
    given()
        .redirects()
        .follow(false)
        .when()
        .get("/observability")
        .then()
        .statusCode(301)
        .header("Location", "/observability/");
  }

  @Test
  void realRoutesAnswerAndAMistypedOneIsNeverTheClient() {
    given()
        .when()
        .get("/observability/api/telemetry/store")
        .then()
        .statusCode(200)
        .contentType(ContentType.JSON);

    // The whole reason quarkus.quinoa.ignored-path-prefixes carries /api: without it this answers
    // 200 with index.html, and a machine client parses the client's not-found page as data.
    //
    // The assertion is "404, and not the CLIENT" rather than "404, never HTML", because what
    // actually comes back is Vert.x' own stock <h1>Resource not found</h1> — text/html, and
    // correct. The content type alone cannot tell the two apart (index.html is text/html too), so
    // the status and the absence of the client are what is pinned.
    String body =
        given().when().get("/observability/api/nope").then().statusCode(404).extract().asString();
    assertFalse(
        body.contains(CLIENT_MARK),
        "a mistyped machine path must not be answered with the client; got: " + body);

    // qits-gateway routes verbatim by prefix, so there is no unprefixed form to fall back to.
    given().when().get("/api/telemetry/store").then().statusCode(404);
  }

  @Test
  void aMistypedMcpPathReachesTheMachineSurfaceAndNeverTheClient() {
    // /mcp is the third entry in ignored-path-prefixes, the one Quinoa's own derivation would not
    // produce — quarkus-mcp-server mounts outside quarkus.rest.path, so if the hand-spelled list
    // ever regressed to the derived pair, this path would fall through to the SPA and answer 200
    // index.html. The MCP server's own answer for a wrong sub-path may be any 4xx; the pinned fact
    // is only that the client is not it.
    var response = given().when().get("/observability/mcp/nope").then().extract();
    assertFalse(
        response.asString().contains(CLIENT_MARK),
        "a mistyped MCP path must not be answered with the client");
    assertTrue(
        response.statusCode() >= 400,
        "a mistyped MCP path must be an error, not a page; got: " + response.statusCode());
  }

  @Test
  void theReadinessEndpointIsWhereTheDeploymentLooksForIt() {
    given()
        .when()
        .get("/observability/q/health/ready")
        .then()
        .statusCode(200)
        .body("status", org.hamcrest.Matchers.equalTo("UP"));
  }

  @Test
  void theApiDocumentAndItsUiAreServedUnderTheSegment() {
    // Both live under quarkus.http.non-application-root-path, which sits OUTSIDE quarkus.rest.path
    // and carries /observability on its own; at / they would be unreachable through qits-gateway.
    given().when().get("/observability/q/openapi").then().statusCode(200);
    given().when().get("/observability/q/swagger-ui/").then().statusCode(200);
  }
}
