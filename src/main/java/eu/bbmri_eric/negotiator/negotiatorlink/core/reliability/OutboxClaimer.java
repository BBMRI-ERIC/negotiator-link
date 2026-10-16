package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxClaimer {

  private final OutboxIntentRepository outboxIntentRepository;

  public OutboxClaimer(OutboxIntentRepository outboxIntentRepository) {
    this.outboxIntentRepository = outboxIntentRepository;
  }

  /**
   * Claims up to {@code limit} due intents, flipping them to IN_PROGRESS with a shared claim token
   * and expiry. Returns a map of rowId → claim token.
   */
  @Transactional
  public Map<Long, UUID> claim(int limit, Duration claimTimeout) {
    Instant now = Instant.now();
    List<Long> rowIds = outboxIntentRepository.selectDispatchableIds(now, limit);
    if (rowIds.isEmpty()) {
      return Map.of();
    }
    UUID token = UUID.randomUUID();
    outboxIntentRepository.markClaimed(rowIds, now.plus(claimTimeout), token);
    Map<Long, UUID> result = new HashMap<>(rowIds.size());
    for (Long rowId : rowIds) {
      result.put(rowId, token);
    }
    return result;
  }

  /**
   * Resets the claim expiry when the worker actually starts processing.Only takes effect when the
   * token still matches. Returns true if the claim was successfully extended, otherwise false.
   */
  @Transactional
  public boolean activateClaim(Long rowId, UUID token, Duration claimTimeout) {
    Instant until = Instant.now().plus(claimTimeout);
    return outboxIntentRepository.extendClaim(rowId, token, until) > 0;
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
