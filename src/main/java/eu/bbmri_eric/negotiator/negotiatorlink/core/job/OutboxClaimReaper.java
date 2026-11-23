package eu.bbmri_eric.negotiator.negotiatorlink.core.job;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxClaimer;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns outbox intents whose delivery claim has expired back to status {@link
 * OutboxStatus#PENDING}.
 *
 * <p>Also runs once at startup to release intents left {@code IN_PROGRESS} after application was
 * restarted.
 */
@Slf4j
@Component
public class OutboxClaimReaper implements ApplicationRunner {

  private final OutboxClaimer claimer;
  private final Duration claimTimeout;
  private final Instant startupCutoff;

  public OutboxClaimReaper(
      OutboxClaimer claimer,
      NegotiatorLinkProperties properties,
      ApplicationContext applicationContext) {
    this.claimer = claimer;
    this.claimTimeout = properties.getOutbox().getClaimTimeout();
    this.startupCutoff = Instant.ofEpochMilli(applicationContext.getStartupDate());
  }

  @Override
  public void run(@Nullable ApplicationArguments args) {
    List<Long> released = claimer.reclaimClaimsStartedBefore(startupCutoff, claimTimeout);
    if (!released.isEmpty()) {
      log.warn(
          "Released {} IN_PROGRESS outbox intent(s) claimed before startup back to PENDING: {}",
          released.size(),
          released);
    }
  }

  @Scheduled(
      fixedDelayString = "${negotiator-link.outbox.claim-reap-interval:60}",
      timeUnit = TimeUnit.SECONDS)
  public void releaseExpiredClaims() {
    List<Long> reclaimed = claimer.releaseExpiredClaims();
    if (!reclaimed.isEmpty()) {
      log.warn(
          "Reclaimed {} outbox intent(s) with expired claims back to PENDING: {}",
          reclaimed.size(),
          reclaimed);
    }
  }
}
