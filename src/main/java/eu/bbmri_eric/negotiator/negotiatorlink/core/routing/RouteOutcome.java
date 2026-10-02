package eu.bbmri_eric.negotiator.negotiatorlink.core.routing;

import java.util.Optional;

/**
 * Result of routing a captured inbound event.
 *
 * <p>{@code eventType} is the resolved event type when it could be determined. It is recorded on
 * the inbox row for the audit trail. It is {@code null} when the type could not be resolved
 * (unparseable body, missing type field).
 */
public record RouteOutcome(boolean processed, String skipReason, String eventType) {

  public static RouteOutcome asProcessed(String eventType) {
    return new RouteOutcome(true, null, eventType);
  }

  public static RouteOutcome skipped(String reason) {
    return new RouteOutcome(false, reason, null);
  }

  public static RouteOutcome skipped(String reason, String eventType) {
    return new RouteOutcome(false, reason, eventType);
  }

  public Optional<String> resolvedEventType() {
    return Optional.ofNullable(eventType);
  }
}
