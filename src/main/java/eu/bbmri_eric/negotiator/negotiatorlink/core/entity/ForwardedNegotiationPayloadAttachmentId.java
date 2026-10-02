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

  @Column(name = "organization_ext_id")
  private String organizationExtId;

  @Column(name = "ticket_id")
  private String ticketId;

  @Column(name = "attachment_id")
  private String attachmentId;
}
