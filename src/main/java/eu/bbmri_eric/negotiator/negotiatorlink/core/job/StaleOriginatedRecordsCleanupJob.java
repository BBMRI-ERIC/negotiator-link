package eu.bbmri_eric.negotiator.negotiatorlink.core.job;

import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class StaleOriginatedRecordsCleanupJob {

  private final OriginatedPostRepository originatedPostRepository;
  private final OriginatedTransitionRepository originatedTransitionRepository;
  private final Duration cutoffAge;

  public StaleOriginatedRecordsCleanupJob(
      OriginatedPostRepository originatedPostRepository,
      OriginatedTransitionRepository originatedTransitionRepository,
      @Value("${negotiator-link.cleanup.cutoff-age:PT24H}") Duration cutoffAge) {
    this.originatedPostRepository = originatedPostRepository;
    this.originatedTransitionRepository = originatedTransitionRepository;
    this.cutoffAge = cutoffAge;
  }

  @Transactional
  @Scheduled(cron = "${negotiator-link.cleanup.cron:0 0 0 * * *}")
  public void deleteStaleRows() {
    Instant cutoff = Instant.now().minus(cutoffAge);
    long deletedPosts = originatedPostRepository.deleteByCreatedAtBefore(cutoff);
    long deletedTransitions = originatedTransitionRepository.deleteByCreatedAtBefore(cutoff);
    log.info(
        "Cleaned up stale originated records older than {}: {} posts, {} transitions",
        cutoffAge,
        deletedPosts,
        deletedTransitions);
  }
}
