package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload;

import java.util.List;

/** Body of a {@code LINK_TICKETS} intent for a PER_RESOURCE ticket strategy. */
public record LinkTicketsPayload(String negotiationId, List<String> resourceSourceIds) {}
