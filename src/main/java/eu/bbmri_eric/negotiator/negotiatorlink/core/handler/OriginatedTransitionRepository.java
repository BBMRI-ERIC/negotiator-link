package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OriginatedTransitionRepository extends JpaRepository<OriginatedTransition, Long> {

  Optional<OriginatedTransition> findByNegotiationIdAndTicketId(
      String negotiationId, String ticketId);

  void deleteByCreatedAtBefore(Instant cutoff);
}
