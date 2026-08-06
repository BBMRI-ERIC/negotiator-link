package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

public record HelpdeskCallbackEvent(
    String ticketId,
    HelpdeskCallbackType type,
    String resourceTransitionEvent,
    String postText,
    String helpdeskActor) {}
