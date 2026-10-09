package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OriginatedPostRepository extends JpaRepository<OriginatedPost, Long> {

  Optional<OriginatedPost>
      findFirstByTextHashAndOrganizationExtIdInAndTicketIdInOrderByCreatedAtAsc(
          String textHash, Set<String> organizationExtIds, Set<String> ticketIds);

  Optional<OriginatedPost> findFirstByTextHashAndOrganizationExtIdAndTicketIdOrderByCreatedAtAsc(
      String textHash, String organizationExtId, String ticketId);

  /**
   * Deletes originated_post rows older than {@code cutoff} only when every CREATE_POST outbox
   * intent targeting the same ticket_id and organization is in a terminal state.
   */
  @Modifying
  @Query(
      value =
          "DELETE FROM originated_post op "
              + "WHERE op.created_at < :cutoff "
              + "AND NOT EXISTS ("
              + "  SELECT 1 FROM outbox_intent oi "
              + "  WHERE oi.intent_type = 'CREATE_POST' "
              + "    AND oi.target_id = op.ticket_id "
              + "    AND oi.organization_ext_id = op.organization_ext_id "
              + "    AND oi.status IN (:nonterminalStatuses)"
              + ")",
      nativeQuery = true)
  long deleteTerminalAndOrphanedByCreatedAtBefore(
      @Param("cutoff") Instant cutoff,
      @Param("nonterminalStatuses") Collection<OutboxStatus> nonterminalStatuses);
}
