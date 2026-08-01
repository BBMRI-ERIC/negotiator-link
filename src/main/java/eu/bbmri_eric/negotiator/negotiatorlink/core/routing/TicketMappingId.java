package eu.bbmri_eric.negotiator.negotiatorlink.core.routing;

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
public class TicketMappingId implements Serializable {

  @Column(name = "negotiation_id")
  private String negotiationId;

  @Column(name = "helpdesk_ticket_id")
  private String helpdeskTicketId;

  @Column(name = "resource_id")
  private String resourceId;
}
