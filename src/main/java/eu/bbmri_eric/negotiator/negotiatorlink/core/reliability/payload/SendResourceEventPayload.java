package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload;

/** Body of a {@code SEND_RESOURCE_EVENT} intent (PER_RESOURCE transition). */
public record SendResourceEventPayload(
    String negotiationId, String resourceSourceId, String event) {}
