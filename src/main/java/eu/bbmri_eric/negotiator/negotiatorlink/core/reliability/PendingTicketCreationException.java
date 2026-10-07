package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

/**
 * Thrown when a Negotiator event arrives before its CREATE_TICKET outbox intent has been delivered.
 * The inbox poller treats this as a transient failure and re-schedules the event with backoff.
 */
public class PendingTicketCreationException extends RuntimeException {
  public PendingTicketCreationException(String negotiationId) {
    super("Ticket(s) not yet created for negotiation '%s'".formatted(negotiationId));
  }
}
