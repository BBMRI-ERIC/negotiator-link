package eu.bbmri_eric.negotiator.negotiatorlink.common.model;

public record TicketStateChangedEvent(String ticketId, String newTicketState)
    implements HelpdeskCallbackEvent {

  @Override
  public HelpdeskCallbackType type() {
    return HelpdeskCallbackType.TICKET_STATE_CHANGED;
  }
}
