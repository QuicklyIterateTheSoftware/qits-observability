package eu.wohlben.qits.domain.telemetry.dto;

import java.util.Map;

/**
 * A span event as stored in the telemetry buffer. The interesting case is the OTel {@code
 * exception} event, whose attributes carry {@code exception.type} / {@code exception.message} /
 * {@code exception.stacktrace} — the structured stack trace that makes telemetry better evidence
 * than log scraping.
 */
public record SpanEvent(String name, long epochNanos, Map<String, String> attributes) {

  /** The OTel semantic-convention name of the event recorded for a thrown exception. */
  public static final String EXCEPTION_EVENT_NAME = "exception";

  public boolean isException() {
    return EXCEPTION_EVENT_NAME.equals(name);
  }
}
