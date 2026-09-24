package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

public record PostMessageEvent(
    String ticketId, String postText, Sender sender, String negotiationState)
    implements HelpdeskCallbackEvent {

  @Override
  public HelpdeskCallbackType type() {
    return HelpdeskCallbackType.POST_MESSAGE;
  }
}
