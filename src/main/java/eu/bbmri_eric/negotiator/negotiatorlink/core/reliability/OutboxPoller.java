package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single background poller that drains the outbox. */
@Slf4j
@Component
public class OutboxPoller {

  private final OutboxIntentRepository outboxIntentRepository;
  private final OutboxDeliverer deliverer;
  private final int batchSize;

  public OutboxPoller(
      OutboxIntentRepository outboxIntentRepository,
      OutboxDeliverer deliverer,
      NegotiatorLinkProperties properties) {
    this.outboxIntentRepository = outboxIntentRepository;
    this.deliverer = deliverer;
    this.batchSize = properties.getOutbox().getBatchSize();
  }

  @Scheduled(fixedDelayString = "${negotiator-link.outbox.poll-delay-ms:1000}")
  public void poll() {
    while (!deliverBatch()) {
      // drain until empty
    }
  }

  /** Delivers one batch. Returns {@code true} when the batch was empty (nothing left to drain). */
  private boolean deliverBatch() {
    List<Long> rowIds =
        outboxIntentRepository.claimDispatchable(Instant.now(), batchSize).stream()
            .map(OutboxIntent::getRowId)
            .toList();
    rowIds.forEach(this::deliverOne);
    return rowIds.isEmpty();
  }

  private void deliverOne(Long rowId) {
    try {
      deliverer.deliver(rowId);
    } catch (Exception e) {
      log.error("Outbox delivery failed for intent {} — recording failure", rowId, e);
      deliverer.recordFailure(rowId, e);
    }
  }
}
