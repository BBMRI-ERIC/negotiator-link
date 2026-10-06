package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.RouteOutcome;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single background poller that processes the inbox. */
@Slf4j
@Component
public class InboxPoller {

  private final InboxEventProcessor processor;
  private final InboxEventRepository inboxEventRepository;
  private final int batchSize;

  public InboxPoller(
      InboxEventProcessor processor,
      InboxEventRepository inboxEventRepository,
      NegotiatorLinkProperties properties) {
    this.processor = processor;
    this.inboxEventRepository = inboxEventRepository;
    this.batchSize = properties.getInbox().getBatchSize();
  }

  @Scheduled(fixedDelayString = "${negotiator-link.inbox.poll-delay-ms:500}")
  public void poll() {
    while (!processBatch()) {
      // process until empty
    }
  }

  /** Processes one batch. Returns {@code true} when the batch was empty (nothing left to process). */
  private boolean processBatch() {
    List<InboxEvent> events = inboxEventRepository.claimReceivedBatch(Instant.now(), batchSize);
    events.forEach(this::processOne);
    return events.isEmpty();
  }

  private void processOne(InboxEvent event) {
    Long rowId = event.getRowId();
    try {
      RouteOutcome outcome = processor.buildOutcome(event);
      processor.commit(rowId, outcome);
    } catch (Exception e) {
      log.error("Inbox processing failed for row {} — recording failure", rowId, e);
      processor.recordFailure(rowId, e);
    }
  }
}
