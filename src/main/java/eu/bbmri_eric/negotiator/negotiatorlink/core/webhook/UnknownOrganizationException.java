package eu.bbmri_eric.negotiator.negotiatorlink.core.webhook;

/**
 * Thrown when an inbound help desk callback addresses an organization that is not configured here,
 * or whose configured help desk type has no registered adapter.
 */
public class UnknownOrganizationException extends RuntimeException {

  public UnknownOrganizationException(String message) {
    super(message);
  }
}
