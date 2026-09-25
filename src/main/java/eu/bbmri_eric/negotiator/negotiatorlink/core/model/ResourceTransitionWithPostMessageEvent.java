package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

public record ResourceTransitionWithPostMessageEvent(
    String ticketId,
    String resourceState,
    String resourceTransitionEvent,
    String postText,
    Sender sender)
    implements HelpdeskCallbackEvent {

  @Override
  public HelpdeskCallbackType type() {
    return HelpdeskCallbackType.RESOURCE_TRANSITION_WITH_POST_MESSAGE;
  }
}
