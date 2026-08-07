package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

public record CreateTicketRequest(
    String title, String negotiationState, String resourceState, String ticketBody) {}
