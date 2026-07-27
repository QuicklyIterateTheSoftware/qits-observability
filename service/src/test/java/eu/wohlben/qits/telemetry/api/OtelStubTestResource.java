package eu.wohlben.qits.telemetry.api;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.Map;

/**
 * A stub parent-qits OTLP collector on an ephemeral port, wired in as {@code
 * otel.exporter.otlp.endpoint} (the env-var-shaped key {@link OtelForwarder} reads — the same key
 * the supervising qits injects as {@code OTEL_EXPORTER_OTLP_ENDPOINT}). Records the last request
 * for assertions; answers 200 on {@code /v1/traces} and 400 on {@code /v1/logs} so both upstream
 * outcomes are observable. (Mirrors the fixture's stub; qits' own OTel SDK stays dark via the
 * main-config {@code quarkus.otel.sdk.disabled=true}.)
 *
 * <p>It also injected {@code otel.resource.attributes} / {@code otel.service.name} while {@code
 * ConfigResource} lived here and relayed them to the SPA. That resource moved to qits-gateway, and
 * the forwarder reads neither, so the stub no longer sets what nothing consumes.
 */
public class OtelStubTestResource implements QuarkusTestResourceLifecycleManager {

  static volatile String lastMethod;
  static volatile String lastPath;
  static volatile String lastContentType;
  static volatile String lastContentEncoding;
  static volatile byte[] lastBody;

  private HttpServer server;

  static void reset() {
    lastMethod = null;
    lastPath = null;
    lastContentType = null;
    lastContentEncoding = null;
    lastBody = null;
  }

  @Override
  public Map<String, String> start() {
    try {
      server = HttpServer.create(new InetSocketAddress(0), 0);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    server.createContext(
        "/",
        exchange -> {
          lastBody = exchange.getRequestBody().readAllBytes();
          lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
          lastContentEncoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
          lastMethod = exchange.getRequestMethod();
          lastPath = exchange.getRequestURI().getPath();
          int status = exchange.getRequestURI().getPath().endsWith("/v1/logs") ? 400 : 200;
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    return Map.of(
        "otel.exporter.otlp.endpoint", "http://localhost:" + server.getAddress().getPort());
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop(0);
    }
  }
}
