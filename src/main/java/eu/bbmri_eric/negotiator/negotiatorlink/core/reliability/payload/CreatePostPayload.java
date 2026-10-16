package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskAttachmentRef;
import java.util.List;

/** Body of a {@code CREATE_POST} intent. */
public record CreatePostPayload(
    String negotiationId,
    String ticketId,
    String signedText,
    List<HelpdeskAttachmentRef> attachments) {}
