package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Persists the outbound intents a handler created. */
@Slf4j
@Service
public class OutboxService {

  private final OutboxIntentRepository outboxIntentRepository;
  private final MeterRegistry meterRegistry;

  public OutboxService(OutboxIntentRepository outboxIntentRepository, MeterRegistry meterRegistry) {
    this.outboxIntentRepository = outboxIntentRepository;
    this.meterRegistry = meterRegistry;
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
      meterRegistry
          .counter("outbox_intents_enqueued_total", "intent_type", intent.outboxIntentType().name())
          .increment();
    }
    outboxIntentRepository.saveAll(outboxIntents);
  }
}
