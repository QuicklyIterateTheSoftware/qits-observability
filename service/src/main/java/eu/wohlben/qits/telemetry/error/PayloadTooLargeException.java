package eu.wohlben.qits.telemetry.error;

/**
 * Domain error mapped to HTTP 413 by the web layer — the same answer the HTTP layer gives a request
 * whose declared body exceeds {@code quarkus.http.limits.max-body-size}, raised from inside the
 * receiver when a compressed body passes that check and only exceeds the ceiling once inflated.
 */
public class PayloadTooLargeException extends DomainException {

  public PayloadTooLargeException(String message) {
    super(413, message);
  }
}
