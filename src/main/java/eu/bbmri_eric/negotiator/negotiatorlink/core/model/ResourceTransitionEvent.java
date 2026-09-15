package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

public record ResourceTransitionEvent(
    String ticketId, String resourceState, String resourceTransitionEvent)
    implements HelpdeskCallbackEvent {

  @Override
  public HelpdeskCallbackType type() {
    return HelpdeskCallbackType.RESOURCE_TRANSITION;
  }
}
