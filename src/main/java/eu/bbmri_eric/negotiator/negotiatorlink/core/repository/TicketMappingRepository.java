package eu.bbmri_eric.negotiator.negotiatorlink.core.repository;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMappingId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketMappingRepository extends JpaRepository<TicketMapping, TicketMappingId> {

  List<TicketMapping> findByIdNegotiationId(String negotiationId);

  List<TicketMapping> findByIdNegotiationIdAndOrganizationId(
      String negotiationId, String organizationId);

  List<TicketMapping> findByIdHelpdeskTicketIdAndOrganizationExtId(
      String helpdeskTicketId, String organizationExtId);

  Optional<TicketMapping> findByIdNegotiationIdAndResourceSourceId(
      String negotiationId, String resourceId);
}
