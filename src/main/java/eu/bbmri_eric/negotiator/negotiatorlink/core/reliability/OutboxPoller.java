package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single background poller that drains the outbox. */
@Slf4j
@Component
public class OutboxPoller {

  private final OutboxClaimer claimer;
  private final OutboxDeliverer deliverer;
  private final int batchSize;
  private final Duration claimTimeout;

  public OutboxPoller(
      OutboxClaimer claimer, OutboxDeliverer deliverer, NegotiatorLinkProperties properties) {
    this.claimer = claimer;
    this.deliverer = deliverer;
    this.batchSize = properties.getOutbox().getBatchSize();
    this.claimTimeout = properties.getOutbox().getClaimTimeout();
  }

  @Scheduled(fixedDelayString = "${negotiator-link.outbox.poll-delay-ms:1000}")
  public void poll() {
    while (!deliverBatch()) {
      // drain until empty
    }
  }

  /** Delivers one batch. Returns {@code true} when the batch was empty (nothing left to drain). */
  private boolean deliverBatch() {
    List<Long> rowIds = claimer.claim(batchSize, claimTimeout);
    rowIds.forEach(this::deliverOne);
    return rowIds.isEmpty();
  }

  private void deliverOne(Long rowId) {
    try {
      DispatchResult result = deliverer.dispatch(rowId);
      deliverer.commit(rowId, result);
    } catch (Exception e) {
      log.error("Outbox delivery failed for intent {} — recording failure", rowId, e);
      deliverer.recordFailure(rowId, e);
    }
  }
}
