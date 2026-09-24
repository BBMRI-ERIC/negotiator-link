package eu.bbmri_eric.negotiator.negotiatorlink.adapter;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreatedTicket;
import java.util.List;

/** Outbound side of a help desk integration. */
public interface HelpdeskClient extends HelpdeskTyped {

  /**
   * Create a new ticket for a resource or organization in a negotiation. Called once per resource
   * (PER_RESOURCE) or once per org (PER_NEGOTIATION) when the negotiation reaches IN_PROGRESS.
   * Returns a CreatedTicket carrying the platform ticket ID (required) and ticket number (if
   * available).
   */
  CreatedTicket createTicket(CreateTicketRequest request, OrganizationConfig config);

  /** Update the negotiation_state display field on an existing ticket. */
  void updateNegotiationState(String ticketId, String negotiationState, OrganizationConfig config);

  /**
   * Update the negotiator_resource_state display field on an existing ticket. Also clears
   * negotiator_resource_transition_event back to null.
   */
  void updateResourceState(String ticketId, String resourceState, OrganizationConfig config);

  /**
   * Update the platform-native ticket state (e.g. close the ticket). Called when a negotiation
   * state maps to a configured ticket state.
   */
  void updateTicketState(String ticketId, String ticketState, OrganizationConfig config);

  /**
   * Add an external comment to a ticket relaying a Negotiator post (from a researcher, admin, or
   * another organization's representative). Visible to all ticket participants.
   */
  void addComment(String ticketId, String commentText, OrganizationConfig config);

  /**
   * Add an internal comment to a ticket for middleware operational notifications (e.g. invalid
   * transition rejection). Visible only to internal help desk staff — not in the Negotiator.
   */
  void addSystemComment(String ticketId, String message, OrganizationConfig config);

  /**
   * Link a set of tickets as related so representatives can navigate between them. Called after all
   * PER_RESOURCE tickets for a negotiation+org are created, with the full list of CreatedTicket
   * objects.
   */
  void linkRelatedTickets(List<CreatedTicket> tickets, OrganizationConfig config);
}
