package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

/**
 * High-level, platform-neutral outbound operation type. Stored as {@code intent_type} on {@code
 * outbox_intent}.
 */
public enum IntentType {
  CREATE_TICKET,
  SET_NEGOTIATION_STATE,
  SET_RESOURCE_STATE,
  SET_TICKET_STATE,
  ADD_COMMENT,
  ADD_SYSTEM_COMMENT,
  LINK_TICKETS,
  CREATE_POST,
  SEND_RESOURCE_EVENT,
  SEND_BULK_RESOURCE_EVENT
}
