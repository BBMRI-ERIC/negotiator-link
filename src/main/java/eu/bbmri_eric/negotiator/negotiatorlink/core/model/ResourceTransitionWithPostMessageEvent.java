package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

import java.util.List;

public record ResourceTransitionWithPostMessageEvent(
    String ticketId,
    String resourceState,
    String resourceTransitionEvent,
    String postText,
    Sender sender,
    List<HelpdeskAttachmentRef> attachments)
    implements HelpdeskCallbackEvent {

  @Override
  public HelpdeskCallbackType type() {
    return HelpdeskCallbackType.RESOURCE_TRANSITION_WITH_POST_MESSAGE;
  }
}
