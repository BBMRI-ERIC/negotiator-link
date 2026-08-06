package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

public record CreateTicketRequest(
    String negotiationId,
    String negotiationDisplayId,
    String resourceName,
    String negotiationState,
    String resourceState,
    String ticketBody) {}
