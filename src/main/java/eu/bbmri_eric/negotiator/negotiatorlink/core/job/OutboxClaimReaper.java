package eu.bbmri_eric.negotiator.negotiatorlink.core.job;

import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxClaimer;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Returns outbox intents whose delivery claim has expired (a poller crashed or hung mid-dispatch)
 * from {@code IN_PROGRESS} back to {@code PENDING} so they are re-claimed. A reclaimed row is
 * redelivered, which carries the accepted at-least-once double-action risk — the externals support
 * no idempotency key, so a lost response cannot be distinguished from a genuine failure.
 */
@Slf4j
@Component
public class OutboxClaimReaper {

  private final OutboxClaimer claimer;

  public OutboxClaimReaper(OutboxClaimer claimer) {
    this.claimer = claimer;
  }

  @Scheduled(fixedDelayString = "${negotiator-link.outbox.claim-reap-interval-ms:30000}")
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
