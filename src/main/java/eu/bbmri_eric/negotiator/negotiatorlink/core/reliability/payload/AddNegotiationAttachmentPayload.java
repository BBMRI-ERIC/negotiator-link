package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload;

import java.util.List;

/**
 * Body of an {@code ADD_COMMENT} intent that forwards negotiation-level attachments. Attachments
 * are carried by reference and re-downloaded at delivery time.
 */
public record AddNegotiationAttachmentPayload(
    String text, String negotiationId, List<String> negotiationAttachmentIds) {}
