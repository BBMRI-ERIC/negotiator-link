package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

import java.util.List;

public record PostMessageEvent(
    String ticketId, String postText, Sender sender, List<HelpdeskAttachmentRef> attachments)
    implements HelpdeskCallbackEvent {

  @Override
  public HelpdeskCallbackType type() {
    return HelpdeskCallbackType.POST_MESSAGE;
  }
}
