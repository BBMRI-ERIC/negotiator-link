package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload;

/** Body of a {@code CREATE_POST} intent. */
public record CreatePostPayload(String negotiationId, String ticketId, String signedText) {}
