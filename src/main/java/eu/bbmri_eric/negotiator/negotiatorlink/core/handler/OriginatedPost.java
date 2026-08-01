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
@Table(name = "originated_posts")
@Getter
@Setter
@NoArgsConstructor
public class OriginatedPost {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "row_id")
  private Long rowId;

  @Column(name = "text_hash", nullable = false)
  private String textHash;

  @Column(name = "organization_id", nullable = false)
  private String organizationId;

  @Column(name = "ticket_id", nullable = false)
  private String ticketId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public OriginatedPost(String textHash, String organizationId, String ticketId) {
    this.textHash = textHash;
    this.organizationId = organizationId;
    this.ticketId = ticketId;
  }

  @PrePersist
  void prePersist() {
    createdAt = Instant.now();
  }
}
