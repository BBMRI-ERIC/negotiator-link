package eu.bbmri_eric.negotiator.negotiatorlink.core.model;

public sealed interface HelpdeskCallbackEvent
    permits ResourceTransitionEvent,
        PostMessageEvent,
        ResourceTransitionWithPostMessageEvent,
        TicketStateChangedEvent {

  String ticketId();

  HelpdeskCallbackType type();
}
