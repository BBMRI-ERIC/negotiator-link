package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.AttachmentForwardingService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Component
public class OutboxDeliverer {

  private final OutboxIntentRepository outboxIntentRepository;
  private final OutboxIntentDispatcher dispatcher;
  private final OutboxService outboxService;
  private final TicketMappingRepository ticketMappingRepository;
  private final AttachmentForwardingService attachmentForwardingService;
  private final FailureClassifier failureClassifier;
  private final PendingTicketCreationCache pendingTicketCreationCache;
  private final int maxAttempts;
  private final Duration baseBackoff;
  private final Duration maxBackoff;

  public OutboxDeliverer(
      OutboxIntentRepository outboxIntentRepository,
      OutboxIntentDispatcher dispatcher,
      OutboxService outboxService,
      TicketMappingRepository ticketMappingRepository,
      AttachmentForwardingService attachmentForwardingService,
      FailureClassifier failureClassifier,
      PendingTicketCreationCache pendingTicketCreationCache,
      NegotiatorLinkProperties properties) {
    this.outboxIntentRepository = outboxIntentRepository;
    this.dispatcher = dispatcher;
    this.outboxService = outboxService;
    this.ticketMappingRepository = ticketMappingRepository;
    this.attachmentForwardingService = attachmentForwardingService;
    this.failureClassifier = failureClassifier;
    this.pendingTicketCreationCache = pendingTicketCreationCache;
    NegotiatorLinkProperties.OutboxProperties outbox = properties.getOutbox();
    this.maxAttempts = outbox.getMaxAttempts();
    this.baseBackoff = outbox.getBaseBackoff();
    this.maxBackoff = outbox.getMaxBackoff();
  }

  /**
   * Dispatches a single already-claimed ({@code IN_PROGRESS}) intent. Returns the DB side effects,
   * or null if the row is gone or the token no longer matches (reclaimed by the reaper).
   */
  public DispatchResult dispatch(Long rowId, UUID claimToken) {
    OutboxIntent intent = outboxIntentRepository.findById(rowId).orElse(null);
    if (intent == null || intent.getStatus() != OutboxStatus.IN_PROGRESS) {
      return null;
    }
    if (!claimToken.equals(intent.getClaimToken())) {
      log.warn("Intent {} token mismatch — row was reclaimed; discarding stale dispatch", rowId);
      return null;
    }
    if (supersededByNewer(intent)) {
      log.debug(
          "Intent {} ({}) superseded by a newer state intent — marking delivered",
          rowId,
          intent.getIntentType());
      return DispatchResult.empty();
    }
    return dispatcher.dispatch(intent);
  }

  /** Persists the dispatch side effects and marks the intent delivered atomically. */
  @Transactional
  public void commit(Long rowId, UUID claimToken, DispatchResult result) {
    if (result == null) {
      return;
    }
    OutboxIntent intent = outboxIntentRepository.findById(rowId).orElse(null);
    if (intent == null || intent.getStatus() != OutboxStatus.IN_PROGRESS) {
      return;
    }
    if (!claimToken.equals(intent.getClaimToken())) {
      log.warn("Intent {} token mismatch on commit — row was reclaimed; discarding result", rowId);
      return;
    }
    ticketMappingRepository.saveAll(result.ticketMappings());
    outboxService.enqueue(result.followUpIntents(), intent.getInboxRowId());
    if (intent.getIntentType() == OutboxIntentType.CREATE_TICKET) {
      String negotiationId = intent.getNegotiationId();
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              pendingTicketCreationCache.removeIfNoPendingCreateTicket(negotiationId);
            }
          });
    }
    result
        .forwardedAttachments()
        .ifPresent(
            record ->
                attachmentForwardingService.recordForwardedAttachments(
                    record.negotiationId(),
                    record.orgExtId(),
                    record.ticketId(),
                    record.attachmentIds()));
    markDelivered(intent);
  }

  /**
   * A transient failure backs off and returns the row to {@code PENDING}. Permanent failures and
   * exhausted retries move to a terminal status.
   */
  @Transactional
  public void recordFailure(Long rowId, UUID claimToken, Throwable error) {
    OutboxIntent intent = outboxIntentRepository.findById(rowId).orElse(null);
    if (intent == null) {
      return;
    }
    if (!claimToken.equals(intent.getClaimToken())) {
      log.warn(
          "Intent {} token mismatch on recordFailure — row was reclaimed; ignoring stale failure",
          rowId);
      return;
    }
    FailureClass failureClass = failureClassifier.classify(error);
    intent.setAttemptCount(intent.getAttemptCount() + 1);
    intent.setLastError(truncate(error.getMessage()));
    intent.setClaimExpiresAt(null);
    intent.setClaimToken(null);
    switch (failureClass) {
      case TRANSIENT -> {
        if (intent.getAttemptCount() >= maxAttempts) {
          intent.setStatus(OutboxStatus.DEAD_LETTER);
          log.error(
              "Intent {} ({}) exhausted {} attempts — DEAD_LETTER",
              intent.getRowId(),
              intent.getIntentType(),
              maxAttempts,
              error);
        } else {
          intent.setStatus(OutboxStatus.PENDING);
          intent.setNextAttemptAt(
              Instant.now()
                  .plus(
                      BackoffPolicy.nextDelay(intent.getAttemptCount(), baseBackoff, maxBackoff)));
          log.warn(
              "Intent {} ({}) transient failure (attempt {}) — retrying",
              intent.getRowId(),
              intent.getIntentType(),
              intent.getAttemptCount(),
              error);
        }
      }
      case PERMANENT -> {
        intent.setStatus(OutboxStatus.FAILED_PERMANENT);
        log.error(
            "Intent {} ({}) permanent failure — FAILED_PERMANENT",
            intent.getRowId(),
            intent.getIntentType(),
            error);
      }
      case UNEXPECTED_ERROR -> {
        intent.setStatus(OutboxStatus.DEAD_LETTER);
        log.error(
            "Intent {} ({}) unexpected error — DEAD_LETTER",
            intent.getRowId(),
            intent.getIntentType(),
            error);
      }
    }
    outboxIntentRepository.save(intent);
  }

  /**
   * A {@code SET_*} state intent is skipped if a newer state intent exists for the same {@code
   * (intentType, organizationExtId, targetId)}. Ordering is by {@code inbox_row_id} (arrival order
   * at the system boundary), preventing a retry backlog from replaying obsolete state. {@code
   * DELIVERED} intents are included so that a retried older intent does not overwrite a state that
   * a newer intent already applied successfully.
   */
  private boolean supersededByNewer(OutboxIntent intent) {
    if (!isStateIntent(intent.getIntentType())) {
      return false;
    }
    return outboxIntentRepository
        .findFirstByIntentTypeAndOrganizationExtIdAndTargetIdAndStatusInOrderByInboxRowIdDesc(
            intent.getIntentType(),
            intent.getOrganizationExtId(),
            intent.getTargetId(),
            List.of(OutboxStatus.PENDING, OutboxStatus.IN_PROGRESS, OutboxStatus.DELIVERED))
        .map(latest -> latest.getInboxRowId() > intent.getInboxRowId())
        .orElse(false);
  }

  private boolean isStateIntent(OutboxIntentType type) {
    return type == OutboxIntentType.SET_NEGOTIATION_STATE
        || type == OutboxIntentType.SET_RESOURCE_STATE
        || type == OutboxIntentType.SET_TICKET_STATE;
  }

  private void markDelivered(OutboxIntent intent) {
    intent.setStatus(OutboxStatus.DELIVERED);
    intent.setDeliveredAt(Instant.now());
    intent.setClaimExpiresAt(null);
    intent.setClaimToken(null);
    outboxIntentRepository.save(intent);
  }

  private String truncate(String message) {
    if (message == null) {
      return null;
    }
    return message.length() > 255 ? message.substring(0, 255) : message;
  }
}
