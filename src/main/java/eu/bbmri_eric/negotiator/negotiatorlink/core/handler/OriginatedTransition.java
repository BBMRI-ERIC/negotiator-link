package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "originated_transitions")
@Getter
@Setter
@NoArgsConstructor
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

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public OriginatedTransition(
      String negotiationId, String ticketId, int expectedCount, String newState) {
    this.negotiationId = negotiationId;
    this.ticketId = ticketId;
    this.expectedCount = expectedCount;
    this.newState = newState;
  }

  @PrePersist
  void prePersist() {
    createdAt = Instant.now();
  }
}
