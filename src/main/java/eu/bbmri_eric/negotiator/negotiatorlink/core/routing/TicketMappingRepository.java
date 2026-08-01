package eu.bbmri_eric.negotiator.negotiatorlink.core.routing;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketMappingRepository extends JpaRepository<TicketMapping, TicketMappingId> {

  List<TicketMapping> findByIdNegotiationId(String negotiationId);

  List<TicketMapping> findByIdHelpdeskTicketIdAndOrganizationExtId(
      String helpdeskTicketId, String organizationExtId);

  Optional<TicketMapping> findByIdNegotiationIdAndIdResourceId(
      String negotiationId, String resourceId);
}
