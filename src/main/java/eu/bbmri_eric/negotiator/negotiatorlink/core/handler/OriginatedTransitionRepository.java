package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OriginatedTransitionRepository extends JpaRepository<OriginatedTransition, Long> {

  Optional<OriginatedTransition> findByNegotiationIdAndTicketId(
      String negotiationId, String ticketId);

  @Modifying
  @Query(
      "UPDATE OriginatedTransition t SET t.receivedCount = t.receivedCount + 1 WHERE t.rowId = :id")
  void incrementReceivedCount(@Param("id") Long id);

  void deleteByCreatedAtBefore(Instant cutoff);
}
