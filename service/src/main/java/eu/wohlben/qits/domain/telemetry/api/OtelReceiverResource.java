package eu.wohlben.qits.domain.telemetry.api;

import com.google.protobuf.InvalidProtocolBufferException;
import eu.wohlben.qits.domain.error.BadRequestException;
import eu.wohlben.qits.domain.telemetry.control.TelemetryDecoder;
import eu.wohlben.qits.domain.telemetry.control.TelemetryStore;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The in-process OTLP/HTTP receiver: the three standard export endpoints under {@code
 * /api/otel/v1/*} — SDKs pointed at {@code
 * OTEL_EXPORTER_OTLP_ENDPOINT=http://<host>:<port>/api/otel} append {@code /v1/<signal>}
 * themselves. Protobuf-only by design: qits pins every launched exporter to {@code http/protobuf}
 * via the injected env vars, so OTLP/JSON (which deviates from proto3 JSON) and gRPC are
 * deliberately not implemented.
 *
 * <p>Hidden from the OpenAPI document: these are wire-protocol endpoints for OTel SDKs, not part of
 * the JSON API the generated Angular client consumes.
 */
@Path("otel/v1")
@Consumes(OtelReceiverResource.PROTOBUF)
@Produces(OtelReceiverResource.PROTOBUF)
public class OtelReceiverResource {

  static final String PROTOBUF = "application/x-protobuf";

  @Inject TelemetryDecoder decoder;

  @Inject TelemetryStore store;

  @POST
  @Path("/traces")
  @Operation(hidden = true)
  public byte[] traces(byte[] body) {
    ExportTraceServiceRequest request = parse(body, ExportTraceServiceRequest::parseFrom);
    store.addSpans(decoder.decodeSpans(request, System.currentTimeMillis()));
    return ExportTraceServiceResponse.getDefaultInstance().toByteArray();
  }

  @POST
  @Path("/logs")
  @Operation(hidden = true)
  public byte[] logs(byte[] body) {
    ExportLogsServiceRequest request = parse(body, ExportLogsServiceRequest::parseFrom);
    store.addLogs(decoder.decodeLogs(request, System.currentTimeMillis()));
    return ExportLogsServiceResponse.getDefaultInstance().toByteArray();
  }

  @POST
  @Path("/metrics")
  @Operation(hidden = true)
  public byte[] metrics(byte[] body) {
    ExportMetricsServiceRequest request = parse(body, ExportMetricsServiceRequest::parseFrom);
    store.addMetrics(decoder.decodeMetrics(request, System.currentTimeMillis()));
    return ExportMetricsServiceResponse.getDefaultInstance().toByteArray();
  }

  @FunctionalInterface
  private interface ProtoParser<T> {
    T parse(byte[] bytes) throws InvalidProtocolBufferException;
  }

  private static <T> T parse(byte[] body, ProtoParser<T> parser) {
    try {
      return parser.parse(gunzipIfNeeded(body == null ? new byte[0] : body));
    } catch (InvalidProtocolBufferException e) {
      throw new BadRequestException("Malformed OTLP protobuf payload");
    }
  }

  /**
   * Decompresses by the gzip magic bytes instead of trusting {@code Content-Encoding} — correct
   * whether or not the server already decompressed, and unambiguous: a valid {@code
   * Export*ServiceRequest} starts with field tag {@code 0x0a}, never {@code 0x1f 0x8b}.
   */
  private static byte[] gunzipIfNeeded(byte[] body) {
    if (body.length < 2 || body[0] != 0x1f || body[1] != (byte) 0x8b) {
      return body;
    }
    try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
      return gz.readAllBytes();
    } catch (IOException e) {
      throw new BadRequestException("Malformed gzip payload");
    }
  }
}
