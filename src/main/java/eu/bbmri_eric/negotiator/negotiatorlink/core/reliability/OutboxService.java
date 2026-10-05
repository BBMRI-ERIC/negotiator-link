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

  public OutboxService(OutboxIntentRepository outboxIntentRepository) {
    this.outboxIntentRepository = outboxIntentRepository;
  }

  /** Enqueues the given intents as {@code PENDING} outbox rows. */
  public void enqueue(List<PendingIntent> intents, InboxEvent sourceEvent) {
    List<OutboxIntent> outboxIntents = new ArrayList<>();
    for (PendingIntent intent : intents) {
      outboxIntents.add(
          new OutboxIntent(
              sourceEvent != null ? sourceEvent.getRowId() : null,
              intent.outboxIntentType(),
              intent.targetKind().name(),
              intent.targetId(),
              intent.negotiationId(),
              intent.organizationExtId(),
              intent.payloadJson()));
    }
    outboxIntentRepository.saveAll(outboxIntents);
  }
}
