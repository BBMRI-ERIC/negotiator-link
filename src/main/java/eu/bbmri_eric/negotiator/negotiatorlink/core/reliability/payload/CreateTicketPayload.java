package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload;

import java.util.List;

/**
 * Body of a {@code CREATE_TICKET} intent. Carries everything the deliverer needs to: 1. create the
 * ticket 2. persist the resulting {@code ticket_mapping} rows 3. post the comments from negotiation
 * in order right after creation. Attachments are carried by reference and re-downloaded at delivery
 * time.
 */
public record CreateTicketPayload(
    String negotiationId,
    String title,
    String negotiationState,
    String resourceState,
    String ticketBody,
    List<String> attachmentIds,
    List<ResourceMappingRef> resources,
    List<String> postTexts) {

  /** The data needed to persist one {@code ticket_mapping} row once the ticket id is known. */
  public record ResourceMappingRef(
      String resourceId, String resourceSourceId, String resourceOrgId) {}
}
