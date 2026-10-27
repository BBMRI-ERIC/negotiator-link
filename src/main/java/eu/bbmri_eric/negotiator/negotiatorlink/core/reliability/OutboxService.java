package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Persists the outbound intents a handler created. */
@Slf4j
@Service
public class OutboxService {

  private final OutboxIntentRepository outboxIntentRepository;
  private final AfterCommitMetrics afterCommitMetrics;

  public OutboxService(
      OutboxIntentRepository outboxIntentRepository, AfterCommitMetrics afterCommitMetrics) {
    this.outboxIntentRepository = outboxIntentRepository;
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
}
