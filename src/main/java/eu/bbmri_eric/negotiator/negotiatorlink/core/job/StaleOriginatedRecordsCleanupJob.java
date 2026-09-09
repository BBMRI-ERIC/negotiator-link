package eu.bbmri_eric.negotiator.negotiatorlink.core.job;

import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class StaleOriginatedRecordsCleanupJob {

  private final OriginatedPostRepository originatedPostRepository;
  private final OriginatedTransitionRepository originatedTransitionRepository;

  @Transactional
  @Scheduled(cron = "0 0 0 * * *")
  public void deleteStaleRows() {
    Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
    long deletedPosts = originatedPostRepository.deleteByCreatedAtBefore(cutoff);
    long deletedTransitions = originatedTransitionRepository.deleteByCreatedAtBefore(cutoff);
    log.info(
        "Cleaned up deduplication rows older than 24h: {} posts, {} transitions",
        deletedPosts,
        deletedTransitions);
  }
}
