package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.IntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxIntentRepository extends JpaRepository<OutboxIntent, Long> {

  /**
   * Fetches a batch of dispatchable intents (status {@code PENDING}, due {@code next_attempt_at}).
   * Ordered by {@code row_id} so a negotiation's intents deliver in enqueue order.
   */
  @Query(
      value =
          "SELECT * FROM outbox_intent WHERE status = 'PENDING' AND next_attempt_at <= :now "
              + "ORDER BY row_id LIMIT :limit",
      nativeQuery = true)
  List<OutboxIntent> claimDispatchable(@Param("now") Instant now, @Param("limit") int limit);

  Optional<OutboxIntent> findFirstByIntentTypeAndTargetIdAndStatusOrderByRowIdDesc(
      IntentType intentType, String targetId, OutboxStatus status);

  long countByStatus(OutboxStatus status);

  long deleteByStatusInAndCreatedAtBefore(List<OutboxStatus> statuses, Instant createdAt);
}
