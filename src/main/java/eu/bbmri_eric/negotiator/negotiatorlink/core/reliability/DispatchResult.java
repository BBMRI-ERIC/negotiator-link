package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import java.util.List;
import java.util.Optional;

/**
 * The DB side effects a {@link OutboxIntentDispatcher#dispatch} produced, deferred so the deliverer
 * can persist them in the transaction after the outbound HTTP call returns.
 */
public record DispatchResult(
    List<TicketMapping> ticketMappings, Optional<ForwardedAttachmentsRecord> forwardedAttachments) {

  private static final DispatchResult EMPTY = new DispatchResult(List.of(), Optional.empty());

  /** No side effects to persist. */
  public static DispatchResult empty() {
    return EMPTY;
  }

  /** The attachments forwarded during dispatch. */
  public record ForwardedAttachmentsRecord(
      String negotiationId, String orgExtId, String ticketId, List<String> attachmentIds) {}
}
