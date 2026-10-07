package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedPost;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OriginatedPostRepository extends JpaRepository<OriginatedPost, Long> {

  Optional<OriginatedPost> findFirstByTextHashAndOrganizationIdAndTicketIdInOrderByCreatedAtAsc(
      String textHash, String organizationId, Set<String> ticketIds);

  Optional<OriginatedPost> findFirstByTicketIdAndOrganizationId(
      String ticketId, String organizationId);

  long deleteByCreatedAtBefore(Instant cutoff);
}
