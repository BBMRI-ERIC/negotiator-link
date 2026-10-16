package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload;

import java.util.List;

/** Body of a {@code SEND_BULK_RESOURCE_EVENT} intent (PER_NEGOTIATION transition). */
public record SendBulkResourceEventPayload(
    String negotiationId, List<Long> resourceIds, String targetState) {}
