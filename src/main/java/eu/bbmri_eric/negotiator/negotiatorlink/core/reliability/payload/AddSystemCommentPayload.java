package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload;

/**
 * Body of an {@code ADD_SYSTEM_COMMENT} intent. The {@code message} to post to a help desk ticket.
 */
public record AddSystemCommentPayload(String message) {}
