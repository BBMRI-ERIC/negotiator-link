package eu.bbmri_eric.negotiator.negotiatorlink.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** Idempotency key for relaying a Negotiator post to a help desk ticket. */
@Entity
@Table(name = "relayed_posts")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class RelayedPost {

  @EmbeddedId private RelayedPostId id;

  @CreatedDate
  @Column(name = "relayed_at", nullable = false, updatable = false)
  private Instant relayedAt;

  public RelayedPost(String organizationExtId, String ticketId, String postId) {
    this.id = new RelayedPostId(organizationExtId, ticketId, postId);
  }
}
