package eu.wohlben.qits.domain.telemetry.dto;

import java.util.Map;

/**
 * A log record as held in the in-memory telemetry store. {@code severityNumber} follows the OTel
 * scale (1–24; ERROR starts at 17), {@code traceId}/{@code spanId} are lowercase hex or empty when
 * the record isn't trace-correlated. {@code receivedAtMillis} is the server-clock ingest stamp used
 * for all windowing (see {@link StoredSpan}).
 */
public record StoredLog(
    long epochNanos,
    int severityNumber,
    String severityText,
    String body,
    String traceId,
    String spanId,
    String serviceName,
    Map<String, String> attributes,
    Map<String, String> resourceAttributes,
    long receivedAtMillis) {

  /** First severityNumber of the OTel ERROR range (ERROR..ERROR4 = 17..20, FATAL above). */
  public static final int SEVERITY_ERROR = 17;

  public boolean isError() {
    return severityNumber >= SEVERITY_ERROR;
  }

  public boolean hasTrace() {
    return traceId != null && !traceId.isEmpty();
  }
}
