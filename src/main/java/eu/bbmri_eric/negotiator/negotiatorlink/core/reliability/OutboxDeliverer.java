package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class OutboxDeliverer {

  private final OutboxIntentRepository outboxIntentRepository;
  private final IntentDispatcher dispatcher;
  private final FailureClassifier failureClassifier;
  private final int maxAttempts;
  private final Duration baseBackoff;
  private final Duration maxBackoff;

  public OutboxDeliverer(
      OutboxIntentRepository outboxIntentRepository,
      IntentDispatcher dispatcher,
      FailureClassifier failureClassifier,
      NegotiatorLinkProperties properties) {
    this.outboxIntentRepository = outboxIntentRepository;
    this.dispatcher = dispatcher;
    this.failureClassifier = failureClassifier;
    NegotiatorLinkProperties.OutboxProperties outbox = properties.getOutbox();
    this.maxAttempts = outbox.getMaxAttempts();
    this.baseBackoff = outbox.getBaseBackoff();
    this.maxBackoff = outbox.getMaxBackoff();
  }

  /** Delivers a single intent. Throws on failure so the poller can record it. */
  @Transactional
  public void deliver(Long rowId) {
    OutboxIntent intent = outboxIntentRepository.findById(rowId).orElse(null);
    if (intent == null || intent.getStatus() != OutboxStatus.PENDING) {
      return;
    }
    if (supersededByNewer(intent)) {
      log.debug(
          "Intent {} ({}) superseded by a newer state intent — marking delivered",
          rowId,
          intent.getIntentType());
      markDelivered(intent);
      return;
    }
    dispatcher.dispatch(intent);
    markDelivered(intent);
  }

  /**
   * A transient failure backs off and retries. Permanent failures and exhausted retries move to a
   * terminal status.
   */
  @Transactional
  public void recordFailure(Long rowId, Throwable error) {
    OutboxIntent intent = outboxIntentRepository.findById(rowId).orElse(null);
    if (intent == null) {
      return;
    }
    FailureClass failureClass = failureClassifier.classify(error);
    intent.setAttemptCount(intent.getAttemptCount() + 1);
    intent.setLastError(truncate(error.getMessage()));
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
            "Intent {} ({}) poison — DEAD_LETTER",
            intent.getRowId(),
            intent.getIntentType(),
            error);
      }
    }
    outboxIntentRepository.save(intent);
  }

  /**
   * A {@code SET_*} state intent is skipped if a newer pending state intent exists for the same
   * {@code (targetId, outboxIntentType)}. Safe because field-set is idempotent, and it prevents a
   * retry backlog from replaying obsolete state.
   */
  private boolean supersededByNewer(OutboxIntent intent) {
    if (!isStateIntent(intent.getIntentType())) {
      return false;
    }
    return outboxIntentRepository
        .findFirstByIntentTypeAndTargetIdAndStatusOrderByRowIdDesc(
            intent.getIntentType(), intent.getTargetId(), OutboxStatus.PENDING)
        .map(latest -> latest.getRowId() > intent.getRowId())
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
    outboxIntentRepository.save(intent);
  }

  private String truncate(String message) {
    if (message == null) {
      return null;
    }
    return message.length() > 255 ? message.substring(0, 255) : message;
  }
}
