package eu.bbmri_eric.negotiator.negotiatorlink.adapter;

import eu.bbmri_eric.negotiator.negotiatorlink.common.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.common.model.Attachment;
import eu.bbmri_eric.negotiator.negotiatorlink.common.model.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.common.model.CreateTicketRequest;
import java.util.List;

/**
 * Outbound side of a help desk integration: write actions against the platform's API.
 *
 * <p>Delivery is at-least-once: when a call succeeds on the platform but its response is lost, the
 * retry logic re-invokes the same action, so any method here may be called more than once for the
 * same logical action. The state-setting methods ({@code update*State}, {@code linkRelatedTickets})
 * are idempotent (re-applying them yields the same result) and the outbox additionally supersedes
 * an obsolete state change with the newer one, so a retry can never set an older state over a newer
 * one; the ticket's state therefore always converges to the latest. Only the content-creating
 * methods ({@code createTicket}, {@code addComment}, {@code addSystemComment}) are non-idempotent:
 * a retry after a lost response may create a second ticket or post a duplicate comment. The current
 * design accepts this residual risk of rare duplicate content, which does not compromise state
 * consistency.
 *
 * <p>Future extension: if a platform natively supports idempotency keys on its API calls, the
 * non-idempotent methods ({@code createTicket}, {@code addComment}) could accept an additional
 * {@code idempotencyKey} parameter generated in the core layer (e.g. derived from the {@code
 * outbox_intent} id) and forwarded to the platform to let it deduplicate the request. This is not
 * implemented yet because the only currently supported platform, Zammad, does not offer such a
 * mechanism and the key would always be ignored.
 */
public interface HelpdeskClient extends HelpdeskTyped {

  /**
   * Create a new ticket for a resource or organization in a negotiation. Called once per resource
   * (PER_RESOURCE) or once per org (PER_NEGOTIATION) when the negotiation reaches IN_PROGRESS. The
   * returned id is reused on every later call for the ticket.
   *
   * @param request the ticket's initial content and attachments
   * @param config the target organization's configuration
   * @return the platform's id for the created ticket
   */
  String createTicket(CreateTicketRequest request, OrganizationConfig config);

  /**
   * Set the negotiation-state display field on an existing ticket. A read-only informational value,
   * which does not change the ticket's native state.
   *
   * @param ticketId the platform id of the ticket
   * @param negotiationState the negotiation-state value to display
   * @param config the target organization's configuration
   */
  void updateNegotiationState(String ticketId, String negotiationState, OrganizationConfig config);

  /**
   * Set the resource-state display field on an existing ticket. A read-only informational value,
   * which does not change the ticket's native state. If the platform carries a pending
   * resource-transition marker used for requesting resource state transitions, clear it here.
   *
   * @param ticketId the platform id of the ticket
   * @param resourceState the resource-state value to display
   * @param config the target organization's configuration
   */
  void updateResourceState(String ticketId, String resourceState, OrganizationConfig config);

  /**
   * Set the ticket's own native state (e.g. close it). Called when a negotiation state or resource
   * state maps to a configured ticket state.
   *
   * @param ticketId the platform id of the ticket
   * @param ticketState the platform-native state to set
   * @param config the target organization's configuration
   */
  void updateTicketState(String ticketId, String ticketState, OrganizationConfig config);

  /**
   * Add an externally visible comment to a ticket, relaying a public Negotiator post. Visible to
   * all ticket participants.
   *
   * @param ticketId the platform id of the ticket
   * @param commentText the message content
   * @param commentType how to interpret {@code commentText} (plain text or HTML)
   * @param config the target organization's configuration
   */
  void addComment(
      String ticketId, String commentText, CommentType commentType, OrganizationConfig config);

  /**
   * Add an externally visible comment together with a set of files, posted as one atomic unit where
   * the platform supports it.
   *
   * @param ticketId the platform id of the ticket
   * @param commentText the message content
   * @param commentType how to interpret {@code commentText} (plain text or HTML)
   * @param attachments the files to attach; may be empty
   * @param config the target organization's configuration
   */
  void addComment(
      String ticketId,
      String commentText,
      CommentType commentType,
      List<Attachment> attachments,
      OrganizationConfig config);

  /**
   * Add an internal, staff-only comment for middleware operational notices (e.g. invalid transition
   * rejection). Visible only to internal help desk staff.
   *
   * @param ticketId the platform id of the ticket
   * @param message the operational message for internal staff
   * @param config the target organization's configuration
   */
  void addSystemComment(String ticketId, String message, OrganizationConfig config);

  /**
   * Link a set of tickets as related so representatives can navigate between them. Called after all
   * PER_RESOURCE tickets for a negotiation+org are created. A no-op with fewer than two ids.
   *
   * @param ticketIds the platform ids of the tickets to link together
   * @param config the target organization's configuration
   */
  void linkRelatedTickets(List<String> ticketIds, OrganizationConfig config);

  /**
   * Download the raw bytes of an attachment from the help desk.
   *
   * @param relativePath the platform-specific, fully-formed relative path to download from, as
   *     emitted earlier by this platform's {@link HelpdeskCallbackAdapter#parseCallback}
   * @param config the target organization's configuration
   * @return the raw attachment content
   */
  byte[] downloadAttachment(String relativePath, OrganizationConfig config);
}
