package eu.bbmri_eric.negotiator.negotiatorlink.core.job;

import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxClaimer;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns outbox intents whose delivery claim has expired back to status {@link
 * OutboxStatus#PENDING}.
 */
@Slf4j
@Component
public class OutboxClaimReaper implements ApplicationRunner {

  private final OutboxClaimer claimer;

  public OutboxClaimReaper(OutboxClaimer claimer) {
    this.claimer = claimer;
  }

  @Override
  public void run(ApplicationArguments args) {
    reclaimExpiredClaims();
  }

  @Scheduled(
      fixedDelayString = "${negotiator-link.outbox.claim-reap-interval:60}",
      timeUnit = TimeUnit.SECONDS)
  public void reclaimExpiredClaims() {
    List<Long> reclaimed = claimer.reclaimExpiredClaims();
    if (!reclaimed.isEmpty()) {
      log.warn(
          "Reclaimed {} outbox intent(s) with expired claims back to PENDING: {}",
          reclaimed.size(),
          reclaimed);
    }
  }
}
