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

/**
 * Negotiation-level attachments already forwarded to a specific ticket. Scoped per ticket (keyed by
 * organization and ticket id) so a failed forward to one ticket does not suppress forwarding to the
 * other tickets of the same negotiation, nor a later retry of the failed one. New records added
 * after ticket creation or negotiation update.
 */
@Entity
@Table(name = "forwarded_negotiation_payload_attachment")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class ForwardedNegotiationPayloadAttachment {

  @EmbeddedId private ForwardedNegotiationPayloadAttachmentId id;

  @Column(name = "negotiation_id", nullable = false, updatable = false)
  private String negotiationId;

  @CreatedDate
  @Column(name = "forwarded_at", nullable = false, updatable = false)
  private Instant forwardedAt;

  public ForwardedNegotiationPayloadAttachment(
      ForwardedNegotiationPayloadAttachmentId id, String negotiationId) {
    this.id = id;
    this.negotiationId = negotiationId;
  }
}
