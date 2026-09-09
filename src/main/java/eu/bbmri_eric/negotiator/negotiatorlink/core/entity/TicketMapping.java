package eu.bbmri_eric.negotiator.negotiatorlink.core.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ticket_mapping")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(of = "id")
public class TicketMapping {

  @EmbeddedId private TicketMappingId id;

  @Column(name = "resource_source_id", nullable = false)
  private String resourceSourceId;

  @Column(name = "organization_id", nullable = false)
  private String organizationId;

  @Column(name = "organization_ext_id", nullable = false)
  private String organizationExtId;

  public TicketMapping(
      TicketMappingId id,
      String resourceSourceId,
      String organizationId,
      String organizationExtId) {
    this.id = id;
    this.resourceSourceId = resourceSourceId;
    this.organizationId = organizationId;
    this.organizationExtId = organizationExtId;
  }
}
