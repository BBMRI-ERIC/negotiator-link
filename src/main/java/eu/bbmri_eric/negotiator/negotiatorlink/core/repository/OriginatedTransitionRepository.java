package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedTransition;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OriginatedTransitionRepository extends JpaRepository<OriginatedTransition, Long> {

  Optional<OriginatedTransition> findByNegotiationIdAndTicketIdAndNewState(
      String negotiationId, String ticketId, String newState);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM OriginatedTransition t WHERE t.rowId = :id")
  Optional<OriginatedTransition> findByIdForUpdate(@Param("id") Long id);

  long deleteByCreatedAtBefore(Instant cutoff);
}
