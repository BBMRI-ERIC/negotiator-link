package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InboxEventRepository extends JpaRepository<InboxEvent, Long> {

  Optional<InboxEvent> findBySourceAndDeliveryId(String source, String deliveryId);

  @Query(
      value =
          "SELECT * FROM inbox_event WHERE status = 'RECEIVED' ORDER BY received_at "
              + "LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<InboxEvent> claimReceivedBatch(@Param("limit") int limit);

  long deleteByStatusInAndReceivedAtBefore(List<InboxStatus> statuses, Instant cutoff);
}
