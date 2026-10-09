package eu.bbmri_eric.negotiator.negotiatorlink.core.reliability;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Tracks negotiation IDs for which a CREATE_TICKET outbox intent is in process. Handlers use this
 * to distinguish "ticket creation pending —> retry" from "negotiation not managed here —> skip".
 *
 * <p>Entries expire after 10 minutes (safety net for stuck intents) or are removed explicitly when
 * CREATE_TICKET is successfully delivered.
 */
@Slf4j
@Component
public class PendingTicketCreationCache {

  static final Duration TTL = Duration.ofMinutes(10);

  private final Cache<String, Boolean> cache;
  private final OutboxIntentRepository outboxIntentRepository;

  public PendingTicketCreationCache(OutboxIntentRepository outboxIntentRepository) {
    this.outboxIntentRepository = outboxIntentRepository;
    this.cache = Caffeine.newBuilder().expireAfterWrite(TTL).build();
  }

  /** Marks a negotiation as having a ticket creation in process. */
  public void track(String negotiationId) {
    cache.put(negotiationId, Boolean.TRUE);
  }

  /** Returns {@code true} if ticket creation is known to be in process for this negotiation. */
  public boolean isTracked(String negotiationId) {
    return cache.getIfPresent(negotiationId) != null;
  }

  /**
   * Clears the cache entry only once no nonterminal CREATE_TICKET intent remains for the
   * negotiation.
   */
  public void removeIfNoPendingCreateTicket(String negotiationId) {
    long remaining =
        outboxIntentRepository.countPendingCreateTicket(negotiationId, OutboxStatus.NONTERMINAL);
    if (remaining == 0) {
      cache.invalidate(negotiationId);
    }
  }

  /**
   * Pre-populates the cache from any CREATE_TICKET intents that survived a restart. Runs once after
   * the application context is fully started, before pollers begin processing.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void warmFromOutbox() {
    List<String> pending =
        outboxIntentRepository.findNegotiationIdsWithPendingCreateTicket(OutboxStatus.NONTERMINAL);
    if (pending.isEmpty()) {
      return;
    }
    pending.forEach(this::track);
    log.info(
        "Warmed pending-ticket cache with {} negotiation(s) from outbox on startup",
        pending.size());
  }
}
