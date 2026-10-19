package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InboxEventRepository extends JpaRepository<InboxEvent, Long> {

  Optional<InboxEvent> findBySourceAndDeliveryId(String source, String deliveryId);

  long countByStatus(InboxStatus status);

  long countByStatusAndAttemptCountGreaterThan(InboxStatus status, int attemptCount);

  @Query(
      value =
          "SELECT * FROM inbox_event WHERE status = 'RECEIVED' "
              + "AND (next_attempt_at IS NULL OR next_attempt_at <= :now) ORDER BY received_at "
              + "LIMIT :limit",
      nativeQuery = true)
  List<InboxEvent> claimReceivedBatch(@Param("now") Instant now, @Param("limit") int limit);

  @Modifying
  @Query(
      value =
          "DELETE FROM inbox_event WHERE status IN (:statuses) AND received_at < :cutoff "
              + "AND NOT EXISTS (SELECT 1 FROM outbox_intent WHERE inbox_row_id = inbox_event.row_id)",
      nativeQuery = true)
  long deleteOrphanedByStatusInAndReceivedAtBefore(
      @Param("statuses") List<String> statuses, @Param("cutoff") Instant cutoff);
}
