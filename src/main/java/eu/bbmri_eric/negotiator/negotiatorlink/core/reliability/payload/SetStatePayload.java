package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload;

/**
 * Body of a {@code SET_NEGOTIATION_STATE} / {@code SET_RESOURCE_STATE} / {@code SET_TICKET_STATE}
 * intent.
 */
public record SetStatePayload(String field, String value) {

  public static final String FIELD_NEGOTIATION_STATE = "negotiation_state";
  public static final String FIELD_RESOURCE_STATE = "resource_state";
  public static final String FIELD_TICKET_STATE = "ticket_state";
}
