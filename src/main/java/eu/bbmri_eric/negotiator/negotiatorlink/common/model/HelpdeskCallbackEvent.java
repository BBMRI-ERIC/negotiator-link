package eu.bbmri_eric.negotiator.negotiatorlink.common.model;

public sealed interface HelpdeskCallbackEvent
    permits ResourceTransitionEvent,
        PostMessageEvent,
        ResourceTransitionWithPostMessageEvent,
        TicketStateChangedEvent {

  String ticketId();

  HelpdeskCallbackType type();
}
