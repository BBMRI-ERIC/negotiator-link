package eu.bbmri_eric.negotiator.negotiatorlink.integration.core.routing;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class TicketMappingRepositoryTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String RESOURCE_ID = "res-1";
  private static final String HELP_DESK_TICKET_1_ID = "ticket-1";
  private static final String ORGANIZATION_EXTERNAL_ID = "bbmri:DE:MHH";

  @Autowired private TicketMappingRepository repository;

  @Test
  void findByIdNegotiationId_whenTwoRowsSameNegotiation_returnsBoth() {
    repository.save(
        createTicketMapping(HELP_DESK_TICKET_1_ID, RESOURCE_ID, ORGANIZATION_EXTERNAL_ID));
    repository.save(createTicketMapping(HELP_DESK_TICKET_1_ID, "res-2", ORGANIZATION_EXTERNAL_ID));
    var results = repository.findByIdNegotiationId(NEGOTIATION_ID);
    assertThat(results).hasSize(2);
  }

  @Test
  void findByIdNegotiationId_whenUnknownNegotiation_returnsEmpty() {
    repository.save(
        createTicketMapping(HELP_DESK_TICKET_1_ID, RESOURCE_ID, ORGANIZATION_EXTERNAL_ID));
    assertThat(repository.findByIdNegotiationId("unknown")).isEmpty();
  }

  @Test
  void findByIdHelpdeskTicketIdAndOrganizationExtId_whenPerNegotiationTicket_returnsAllResources() {
    repository.save(
        createTicketMapping(HELP_DESK_TICKET_1_ID, RESOURCE_ID, ORGANIZATION_EXTERNAL_ID));
    repository.save(createTicketMapping(HELP_DESK_TICKET_1_ID, "res-2", ORGANIZATION_EXTERNAL_ID));
    repository.save(createTicketMapping("ticket-2", "res-3", "bbmri:NL:AMC"));
    var results =
        repository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            HELP_DESK_TICKET_1_ID, ORGANIZATION_EXTERNAL_ID);
    assertThat(results).hasSize(2);
    assertThat(results)
        .allMatch(m -> m.getId().getHelpdeskTicketId().equals(HELP_DESK_TICKET_1_ID));
  }

  @Test
  void findByIdNegotiationIdAndResourceSourceId_whenExists_returnsRow() {
    repository.save(
        createTicketMapping(HELP_DESK_TICKET_1_ID, RESOURCE_ID, ORGANIZATION_EXTERNAL_ID));
    var result = repository.findByIdNegotiationIdAndResourceSourceId(NEGOTIATION_ID, RESOURCE_ID);
    assertThat(result).isPresent();
    assertThat(result.get().getResourceSourceId()).isEqualTo(RESOURCE_ID);
  }

  @Test
  void findByIdNegotiationIdAndResourceSourceId_whenUnknown_returnsEmpty() {
    assertThat(repository.findByIdNegotiationIdAndResourceSourceId(NEGOTIATION_ID, "res-x"))
        .isEmpty();
  }

  private static TicketMapping createTicketMapping(
      String ticketId, String resourceId, String orgExtId) {
    var id = new TicketMappingId(NEGOTIATION_ID, ticketId, "prefix-" + resourceId);
    return new TicketMapping(id, resourceId, "org-1", orgExtId);
  }
}
