package eu.bbmri_eric.negotiator.negotiatorlink.common.model;

public record ResourceTransitionEvent(String ticketId, String resourceTransitionEvent)
    implements HelpdeskCallbackEvent {

  @Override
  public HelpdeskCallbackType type() {
    return HelpdeskCallbackType.RESOURCE_TRANSITION;
  }
}
