package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

public record HelpdeskCallbackEvent(
    String ticketId,
    HelpdeskCallbackType type,
    String resourceState,
    String resourceTransitionEvent,
    String postText,
    Sender sender) {}
