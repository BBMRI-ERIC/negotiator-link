package eu.bbmri_eric.negotiator.negotiatorlink.core.job;

import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class StaleRecordsCleanupJob {

  private final OriginatedPostRepository originatedPostRepository;
  private final InboxEventRepository inboxEventRepository;
  private final OutboxIntentRepository outboxIntentRepository;
  private final Duration cutoffAge;

  public StaleRecordsCleanupJob(
      OriginatedPostRepository originatedPostRepository,
      InboxEventRepository inboxEventRepository,
      OutboxIntentRepository outboxIntentRepository,
      @Value("${negotiator-link.cleanup.cutoff-age:PT96)H}") Duration cutoffAge) {
    this.originatedPostRepository = originatedPostRepository;
    this.inboxEventRepository = inboxEventRepository;
    this.outboxIntentRepository = outboxIntentRepository;
    this.cutoffAge = cutoffAge;
  }

  @Transactional
  @Scheduled(cron = "${negotiator-link.cleanup.cron:0 0 0 * * *}")
  public void deleteStaleRows() {
    Instant cutoff = Instant.now().minus(cutoffAge);
    long deletedPosts =
        originatedPostRepository.deleteTerminalAndOrphanedByCreatedAtBefore(
            cutoff, OutboxStatus.NON_TERMINAL.stream().map(Enum::name).toList());
    long deletedOutbox =
        outboxIntentRepository.deleteByStatusInAndCreatedAtBefore(
            List.of(OutboxStatus.DELIVERED, OutboxStatus.FAILED_PERMANENT), cutoff);
    long deletedInbox =
        inboxEventRepository.deleteOrphanedByStatusInAndReceivedAtBefore(
            List.of(InboxStatus.PROCESSED.name(), InboxStatus.SKIPPED.name()), cutoff);
    log.info(
        "Cleaned up stale records older than {}: {} posts, {} inbox events, {} outbox intents",
        cutoffAge,
        deletedPosts,
        deletedInbox,
        deletedOutbox);
  }
}
