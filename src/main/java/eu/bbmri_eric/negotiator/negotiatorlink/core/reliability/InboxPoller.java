package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Single background poller that drains the inbox. */
@Slf4j
@Component
public class InboxPoller {

  private final InboxEventProcessor processor;
  private final int batchSize;

  public InboxPoller(
      InboxEventProcessor processor,
      @Value("${negotiator-link.inbox.batch-size:50}") int batchSize) {
    this.processor = processor;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${negotiator-link.inbox.poll-delay-ms:1000}")
  public void poll() {
    List<Long> claimed = processor.claimBatch(batchSize);
    for (Long rowId : claimed) {
      processOne(rowId);
    }
  }

  private void processOne(Long rowId) {
    try {
      processor.process(rowId);
    } catch (Exception e) {
      log.error("Inbox processing failed for row {} — recording FAILED", rowId, e);
      processor.markFailed(rowId, e.getMessage());
    }
  }
}
