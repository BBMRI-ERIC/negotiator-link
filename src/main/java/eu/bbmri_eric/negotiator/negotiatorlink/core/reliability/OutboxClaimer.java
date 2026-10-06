package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxClaimer {

  private final OutboxIntentRepository outboxIntentRepository;

  public OutboxClaimer(OutboxIntentRepository outboxIntentRepository) {
    this.outboxIntentRepository = outboxIntentRepository;
  }

  /** Claims up to {@code limit} due intents, flipping them to IN_PROGRESS with a claim expiry. */
  @Transactional
  public List<Long> claim(int limit, Duration claimTimeout) {
    Instant now = Instant.now();
    List<Long> rowIds = outboxIntentRepository.selectDispatchableIds(now, limit);
    if (!rowIds.isEmpty()) {
      outboxIntentRepository.markClaimed(rowIds, now.plus(claimTimeout));
    }
    return rowIds;
  }

  /** Change intents status with an expired claim to PENDING. Returns the reclaimed ids. */
  @Transactional
  public List<Long> reclaimExpiredClaims() {
    List<Long> rowIds = outboxIntentRepository.selectExpiredClaimIds(Instant.now());
    if (!rowIds.isEmpty()) {
      outboxIntentRepository.releaseClaims(rowIds);
    }
    return rowIds;
  }
}
