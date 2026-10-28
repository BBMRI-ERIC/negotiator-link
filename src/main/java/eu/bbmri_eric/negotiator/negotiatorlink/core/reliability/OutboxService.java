package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil.serialize;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationLinkUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persists the outbound intents a handler created. */
@Slf4j
@Service
public class OutboxService {

  private static final String REQUIRED_FORM_MESSAGE_TEMPLATE =
      "Resource state transition with event '%s' failed, because the required form needs to be "
          + "submitted before the transition can be completed.\n"
          + "Please submit the form in the Negotiator and try again.\n"
          + "Link: %s";

  private final OutboxIntentRepository outboxIntentRepository;
  private final TicketMappingRepository ticketMappingRepository;
  private final AfterCommitMetrics afterCommitMetrics;

  public OutboxService(
      OutboxIntentRepository outboxIntentRepository,
      TicketMappingRepository ticketMappingRepository,
      AfterCommitMetrics afterCommitMetrics) {
    this.outboxIntentRepository = outboxIntentRepository;
    this.ticketMappingRepository = ticketMappingRepository;
    this.afterCommitMetrics = afterCommitMetrics;
  }

  /** Enqueues the given intents as {@code PENDING} outbox rows. */
  public void enqueue(List<PendingIntent> intents, InboxEvent sourceEvent) {
    enqueue(intents, sourceEvent.getRowId());
  }

  /** Enqueues the given intents as {@code PENDING} outbox rows. */
  public void enqueue(List<PendingIntent> intents, Long inboxRowId) {
    if (intents.isEmpty()) {
      return;
    }
    List<OutboxIntent> outboxIntents = new ArrayList<>();
    for (PendingIntent intent : intents) {
      outboxIntents.add(
          new OutboxIntent(
              inboxRowId,
              intent.outboxIntentType(),
              intent.targetKind().name(),
              intent.targetId(),
              intent.negotiationId(),
              intent.organizationExtId(),
              intent.payloadJson()));
      afterCommitMetrics.incrementAfterCommit(
          "outbox_intents_enqueued_total", "intent_type", intent.outboxIntentType().name());
    }
    outboxIntentRepository.saveAll(outboxIntents);
  }

  /**
   * Enqueues a user-facing {@code ADD_SYSTEM_COMMENT} on the helpdesk ticket when {@code
   * SEND_RESOURCE_EVENT} intent failed because a required form has not been submitted.
   *
   * @param negotiation the negotiation behind the failed intent, or {@code null} to omit the link
   */
  @Transactional
  public void enqueueRequiredFormComment(Long rowId, EntityModelNegotiationDTO negotiation) {
    OutboxIntent intent = outboxIntentRepository.findById(rowId).orElse(null);
    if (intent == null || intent.getIntentType() != OutboxIntentType.SEND_RESOURCE_EVENT) {
      return;
    }

    SendResourceEventPayload payload =
        PayloadUtil.deserialize(intent.getPayload(), SendResourceEventPayload.class);
    Optional<TicketMapping> mapping =
        ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            payload.negotiationId(), payload.resourceSourceId());
    if (mapping.isEmpty()) {
      log.warn(
          "Intent {} — no ticket mapping for negotiation '{}' resource '{}'; "
              + "skipping required-form comment",
          rowId,
          payload.negotiationId(),
          payload.resourceSourceId());
      return;
    }
    String ticketId = mapping.get().getId().getHelpdeskTicketId();

    String uiLink = NegotiationLinkUtil.uiLink(negotiation).orElse("");
    String message =
        REQUIRED_FORM_MESSAGE_TEMPLATE.formatted(PostUtil.enumToText(payload.event()), uiLink);

    PendingIntent comment =
        new PendingIntent(
            OutboxIntentType.ADD_SYSTEM_COMMENT,
            TargetKind.TICKET,
            ticketId,
            payload.negotiationId(),
            intent.getOrganizationExtId(),
            serialize(new AddSystemCommentPayload(message)));
    enqueue(List.of(comment), intent.getInboxRowId());
    log.info(
        "Intent {} — required form not submitted; enqueued ADD_SYSTEM_COMMENT for ticket '{}'",
        rowId,
        ticketId);
  }
}
