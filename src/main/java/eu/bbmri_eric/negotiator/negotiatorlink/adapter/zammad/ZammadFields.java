package eu.bbmri_eric.negotiator.negotiatorlink.adapter.zammad;

/**
 * Zammad custom-field names shared by the outbound {@link ZammadClient} and the inbound {@link
 * ZammadCallbackAdapter}.
 */
final class ZammadFields {

  static final String NEGOTIATION_STATE = "negotiation_state";
  static final String RESOURCE_STATE = "negotiator_resource_state";
  static final String TRANSITION_EVENT = "negotiator_resource_transition_event";

  private ZammadFields() {}
}
