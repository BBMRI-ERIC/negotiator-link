package eu.bbmri_eric.negotiator.negotiatorlink.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ForwardedNegotiationPayloadAttachmentId implements Serializable {

  @Column(name = "negotiation_id")
  private String negotiationId;

  @Column(name = "attachment_id")
  private String attachmentId;
}
