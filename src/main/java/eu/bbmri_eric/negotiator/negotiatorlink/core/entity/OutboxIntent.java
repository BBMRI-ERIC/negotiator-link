package eu.bbmri_eric.negotiator.negotiatorlink.core.entity;

import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One row per pending external action, written atomically with the inbox row it originates from.
 */
@Entity
@Table(name = "outbox_intent")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "rowId")
public class OutboxIntent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "row_id")
  private Long rowId;

  @Column(name = "inbox_row_id", nullable = false)
  private Long inboxRowId;

  @Enumerated(EnumType.STRING)
  @Column(name = "intent_type", nullable = false)
  private OutboxIntentType intentType;

  @Column(name = "target_kind", nullable = false)
  private String targetKind;

  @Column(name = "target_id")
  private String targetId;

  @Column(name = "negotiation_id", nullable = false)
  private String negotiationId;

  @Column(name = "organization_ext_id", nullable = false)
  private String organizationExtId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false)
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private OutboxStatus status;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  /** Claim deadline while {@link OutboxStatus#IN_PROGRESS}; null otherwise. */
  @Column(name = "claim_expires_at")
  private Instant claimExpiresAt;

  /** Unique token for this claim; guards commit/failure against expired-and-reclaimed rows. */
  @Column(name = "claim_token")
  private UUID claimToken;

  @Column(name = "last_error")
  private String lastError;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "delivered_at")
  private Instant deliveredAt;

  public OutboxIntent(
      Long inboxRowId,
      OutboxIntentType outboxIntentType,
      String targetKind,
      String targetId,
      String negotiationId,
      String organizationExtId,
      String payload) {
    this.inboxRowId = inboxRowId;
    this.intentType = outboxIntentType;
    this.targetKind = targetKind;
    this.targetId = targetId;
    this.negotiationId = negotiationId;
    this.organizationExtId = organizationExtId;
    this.payload = payload;
    this.status = OutboxStatus.PENDING;
    this.attemptCount = 0;
    this.nextAttemptAt = Instant.now();
  }
}
