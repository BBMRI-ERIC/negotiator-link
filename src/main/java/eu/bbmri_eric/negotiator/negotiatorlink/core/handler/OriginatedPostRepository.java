package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OriginatedPostRepository extends JpaRepository<OriginatedPost, Long> {

  Optional<OriginatedPost> findFirstByTextHashAndOrganizationIdOrderByCreatedAtAsc(
      String textHash, String organizationId);

  void deleteByCreatedAtBefore(Instant cutoff);
}
