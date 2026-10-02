package eu.bbmri_eric.negotiator.negotiatorlink.core.entity;

import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** One row per authenticated inbound delivery, captured before any processing. */
@Entity
@Table(
    name = "inbox_event",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_inbox_delivery",
            columnNames = {"source", "delivery_id"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "rowId")
public class InboxEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "row_id")
  private Long rowId;

  @Column(name = "source", nullable = false)
  private String source;

  @Column(name = "delivery_id", nullable = false)
  private String deliveryId;

  @Column(name = "event_type")
  private String eventType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false)
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private InboxStatus status;

  @Column(name = "skip_reason")
  private String skipReason;

  @CreatedDate
  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  @Column(name = "processed_at")
  private Instant processedAt;

  public InboxEvent(String source, String deliveryId, String eventType, String payload) {
    this.source = source;
    this.deliveryId = deliveryId;
    this.eventType = eventType;
    this.payload = payload;
    this.status = InboxStatus.RECEIVED;
  }
}
