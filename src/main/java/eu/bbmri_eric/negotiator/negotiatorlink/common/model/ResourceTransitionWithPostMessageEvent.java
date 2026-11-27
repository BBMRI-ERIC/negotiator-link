package eu.bbmri_eric.negotiator.negotiatorlink.common.model;

import java.util.List;

public record ResourceTransitionWithPostMessageEvent(
    String ticketId,
    String resourceTransitionEvent,
    String postText,
    Sender sender,
    List<HelpdeskAttachmentRef> attachments,
    PostType postType)
    implements HelpdeskCallbackEvent {

  @Override
  public HelpdeskCallbackType type() {
    return HelpdeskCallbackType.RESOURCE_TRANSITION_WITH_POST_MESSAGE;
  }
}
