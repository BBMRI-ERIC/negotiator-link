package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxIntentRepository extends JpaRepository<OutboxIntent, Long> {

  @Query(
      value =
          "SELECT row_id FROM outbox_intent "
              + "WHERE status = 'PENDING' AND next_attempt_at <= :now "
              + "ORDER BY row_id LIMIT :limit FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<Long> selectDispatchableIds(@Param("now") Instant now, @Param("limit") int limit);

  /** Flips the given rows to {@code IN_PROGRESS} and stamps the claim expiry and token. */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "UPDATE outbox_intent SET status = 'IN_PROGRESS', claim_expires_at = :claimUntil, claim_token = :token "
              + "WHERE row_id IN (:rowIds)",
      nativeQuery = true)
  void markClaimed(
      @Param("rowIds") Collection<Long> rowIds,
      @Param("claimUntil") Instant claimUntil,
      @Param("token") UUID token);

  /** Extends the claim expiry for a row only when the token still matches. */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "UPDATE outbox_intent SET claim_expires_at = :claimUntil "
              + "WHERE row_id = :rowId AND claim_token = :token AND status = 'IN_PROGRESS'",
      nativeQuery = true)
  int extendClaim(
      @Param("rowId") Long rowId,
      @Param("token") UUID token,
      @Param("claimUntil") Instant claimUntil);

  /** Selects and locks IN_PROGRESS rows whose claim has expired. */
  @Query(
      value =
          "SELECT row_id FROM outbox_intent "
              + "WHERE status = 'IN_PROGRESS' AND claim_expires_at < :now "
              + "FOR UPDATE SKIP LOCKED",
      nativeQuery = true)
  List<Long> selectExpiredClaimIds(@Param("now") Instant now);

  /** Returns the given rows to {@code PENDING}, clearing their claim, so they are re-claimed. */
  @Modifying(clearAutomatically = true)
  @Query(
      value =
          "UPDATE outbox_intent SET status = 'PENDING', claim_expires_at = NULL, claim_token = NULL "
              + "WHERE row_id IN (:rowIds)",
      nativeQuery = true)
  void releaseClaims(@Param("rowIds") Collection<Long> rowIds);

  Optional<OutboxIntent>
      findFirstByIntentTypeAndOrganizationExtIdAndTargetIdAndStatusInOrderByInboxRowIdDesc(
          OutboxIntentType intentType,
          String organizationExtId,
          String targetId,
          Collection<OutboxStatus> statuses);

  @Query(
      value = "SELECT row_id, negotiation_id FROM outbox_intent WHERE row_id IN (:rowIds)",
      nativeQuery = true)
  List<Object[]> findRowIdAndNegotiationIdByRowIdIn(@Param("rowIds") Set<Long> rowIds);

  long countByStatusIn(Collection<OutboxStatus> statuses);

  long deleteByStatusInAndCreatedAtBefore(List<OutboxStatus> statuses, Instant createdAt);

  @Query(
      value =
          "SELECT DISTINCT negotiation_id FROM outbox_intent "
              + "WHERE intent_type = 'CREATE_TICKET' AND status IN (:statuses)",
      nativeQuery = true)
  List<String> findNegotiationIdsWithPendingCreateTicket(
      @Param("statuses") Collection<OutboxStatus> statuses);
}
