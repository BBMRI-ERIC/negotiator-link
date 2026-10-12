package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedPost;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OriginatedPostRepository extends JpaRepository<OriginatedPost, Long> {

  /**
   * Finds the earliest originated_post whose (ticket_id, organization_ext_id) pair matches one of
   * the valid pairs from ticket_mapping, preventing cross-product false positives.
   *
   * <p>The two arrays must be parallel: {@code ticketIds[i]} and {@code orgExtIds[i]} form one
   * valid pair.
   */
  @Query(
      value =
          "SELECT op.* FROM originated_post op "
              + "JOIN unnest(:ticketIds, :orgExtIds) AS valid_pair(ticket_id, org_ext_id) "
              + "  ON op.ticket_id = valid_pair.ticket_id "
              + " AND op.organization_ext_id = valid_pair.org_ext_id "
              + "WHERE op.text_hash = :textHash "
              + "ORDER BY op.created_at ASC "
              + "LIMIT 1",
      nativeQuery = true)
  Optional<OriginatedPost> findFirstByTextHashAndValidTicketOrgPairs(
      @Param("textHash") String textHash,
      @Param("ticketIds") String[] ticketIds,
      @Param("orgExtIds") String[] orgExtIds);

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
              + "    AND oi.payload->>'ticketId' = op.ticket_id "
              + "    AND oi.organization_ext_id = op.organization_ext_id "
              + "    AND oi.status IN (:nonterminalStatuses)"
              + ")",
      nativeQuery = true)
  long deleteTerminalAndOrphanedByCreatedAtBefore(
      @Param("cutoff") Instant cutoff,
      @Param("nonterminalStatuses") Collection<String> nonterminalStatuses);
}
