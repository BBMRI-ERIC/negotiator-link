package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.OriginatedTransition;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OriginatedTransitionRepository extends JpaRepository<OriginatedTransition, Long> {

  Optional<OriginatedTransition> findByNegotiationIdAndTicketId(
      String negotiationId, String ticketId);

  Optional<OriginatedTransition> findByNegotiationIdAndTicketIdAndNewState(
      String negotiationId, String ticketId, String newState);

  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE OriginatedTransition t SET t.receivedCount = t.receivedCount + 1 WHERE t.rowId = :id")
  void incrementReceivedCount(@Param("id") Long id);

  @Modifying(clearAutomatically = true)
  @Query(
      "DELETE FROM OriginatedTransition t WHERE t.negotiationId = :negotiationId AND t.ticketId = :ticketId AND t.newState = :newState")
  void deleteByNegotiationIdAndTicketIdAndNewState(
      @Param("negotiationId") String negotiationId,
      @Param("ticketId") String ticketId,
      @Param("newState") String newState);

  void deleteByCreatedAtBefore(Instant cutoff);
}
