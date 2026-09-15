package eu.bbmri_eric.negotiator.negotiatorlink.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "originated_transition")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "rowId")
public class OriginatedTransition {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "row_id")
  private Long rowId;

  @Column(name = "negotiation_id", nullable = false)
  private String negotiationId;

  @Column(name = "ticket_id", nullable = false)
  private String ticketId;

  @Column(name = "expected_count", nullable = false)
  private int expectedCount;

  @Column(name = "received_count", nullable = false)
  private int receivedCount;

  @Column(name = "new_state", nullable = false)
  private String newState;

  @CreatedDate
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public OriginatedTransition(
      String negotiationId, String ticketId, int expectedCount, String newState) {
    this.negotiationId = negotiationId;
    this.ticketId = ticketId;
    this.expectedCount = expectedCount;
    this.newState = newState;
  }
}
