package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single background poller that drains the inbox. */
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

  @Scheduled(fixedDelayString = "${negotiator-link.inbox.poll-delay-ms:1000}")
  public void poll() {
    while (!processBatch()) {
      // drain until empty
    }
  }

  /** Processes one batch. Returns {@code true} when the batch was empty (nothing left to drain). */
  private boolean processBatch() {
    List<Long> rowIds =
        inboxEventRepository.claimReceivedBatch(Instant.now(), batchSize).stream()
            .map(InboxEvent::getRowId)
            .toList();
    rowIds.forEach(this::processOne);
    return rowIds.isEmpty();
  }

  private void processOne(Long rowId) {
    try {
      processor.process(rowId);
    } catch (Exception e) {
      log.error("Inbox processing failed for row {} — recording failure", rowId, e);
      processor.recordFailure(rowId, e);
    }
  }
}
