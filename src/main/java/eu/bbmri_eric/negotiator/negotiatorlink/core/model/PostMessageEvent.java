package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

public record PostMessageEvent(String ticketId, String postText, Sender sender)
    implements HelpdeskCallbackEvent {

  @Override
  public HelpdeskCallbackType type() {
    return HelpdeskCallbackType.POST_MESSAGE;
  }
}
