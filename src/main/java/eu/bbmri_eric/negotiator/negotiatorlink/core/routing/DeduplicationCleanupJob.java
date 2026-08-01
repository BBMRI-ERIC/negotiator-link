package eu.bbmri_eric.negotiator.negotiatorlink.core.routing;

import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedTransitionRepository;
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
public class DeduplicationCleanupJob {

  private final OriginatedPostRepository originatedPostRepository;
  private final OriginatedTransitionRepository originatedTransitionRepository;

  @Transactional
  @Scheduled(cron = "0 0 0 * * *")
  public void deleteStaleRows() {
    Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
    originatedPostRepository.deleteByCreatedAtBefore(cutoff);
    originatedTransitionRepository.deleteByCreatedAtBefore(cutoff);
    log.info("Cleaned up deduplication rows older than 24 hours");
  }
}
