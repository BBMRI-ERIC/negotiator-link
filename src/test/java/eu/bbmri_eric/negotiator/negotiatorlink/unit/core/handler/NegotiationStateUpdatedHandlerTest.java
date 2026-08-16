package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpDeskAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationStateUpdatedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreatedTicket;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationTicketBodyFormatter;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NegotiationStateUpdatedHandlerTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String DISPLAY_ID = "NEGO-1";
  private static final String NEGOTIATION_STATE = "IN_PROGRESS";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final Long ORG_INTERNAL_ID = 10L;
  private static final String TICKET_ID = "ticket-42";
  private static final String RESOURCE_STATE = "SUBMITTED";

  @Mock private NegotiatorClient negotiatorClient;
  @Mock private OrganizationConfigRegistry registry;
  @Mock private HelpDeskAdapter adapter;
  @Mock private TicketMappingRepository ticketMappingRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private NegotiationStateUpdatedHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new NegotiationStateUpdatedHandler(
            negotiatorClient,
            registry,
            Map.of(HelpDeskType.ZAMMAD, adapter),
            ticketMappingRepository,
            objectMapper);
  }

  @Test
  void handle_perResource_multipleResources_createsSeparateTicketsAndLinks() {
    var resource1 = createResource("r-1", 1L);
    var resource2 = createResource("r-2", 2L);
    var negotiation = createNegotiation();
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    var ticket1 = new CreatedTicket("ticket-1", "1");
    var ticket2 = new CreatedTicket("ticket-2", "2");

    stubHappyPath(negotiation, config, List.of(resource1, resource2));
    when(adapter.createTicket(any(CreateTicketRequest.class), eq(config)))
        .thenReturn(ticket1)
        .thenReturn(ticket2);

    handler.handle(approveEventData());

    verify(adapter).createTicket(eq(perResourceRequest(negotiation, resource1)), eq(config));
    verify(adapter).createTicket(eq(perResourceRequest(negotiation, resource2)), eq(config));
    verify(adapter).linkRelatedTickets(eq(List.of(ticket1, ticket2)), eq(config));
    verify(ticketMappingRepository).save(eq(mappingFor(ticket1, "1", resource1)));
    verify(ticketMappingRepository).save(eq(mappingFor(ticket2, "2", resource2)));
  }

  @Test
  void handle_perNegotiation_multipleResources_createsSingleTicketForAllResources() {
    var resource1 = createResource("r-1", 1L);
    var resource2 = createResource("r-2", 2L);
    var negotiation = createNegotiation();
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var ticket = new CreatedTicket(TICKET_ID, "42");

    stubHappyPath(negotiation, config, List.of(resource1, resource2));
    when(adapter.createTicket(any(CreateTicketRequest.class), eq(config))).thenReturn(ticket);

    handler.handle(approveEventData());

    var expectedRequest = perNegotiationRequest(negotiation, List.of(resource1, resource2));

    verify(adapter, times(1)).createTicket(eq(expectedRequest), eq(config));
    verify(adapter, never()).linkRelatedTickets(any(), any());
    verify(ticketMappingRepository).save(eq(mappingFor(ticket, "1", resource1)));
    verify(ticketMappingRepository).save(eq(mappingFor(ticket, "2", resource2)));
  }

  @Test
  void handle_perResource_singleResource_doesNotLink() {
    var resource = createResource("r-1", 1L);
    var negotiation = createNegotiation();
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    var ticket = new CreatedTicket(TICKET_ID, "42");

    stubHappyPath(negotiation, config, List.of(resource));
    when(adapter.createTicket(any(CreateTicketRequest.class), eq(config))).thenReturn(ticket);

    handler.handle(approveEventData());

    verify(adapter, times(1))
        .createTicket(eq(perResourceRequest(negotiation, resource)), eq(config));
    verify(adapter, never()).linkRelatedTickets(any(), any());
  }

  @Test
  void handle_orgNotInConfig_skipsOrg() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    stubResources(createNegotiation(), config, List.of(createResource("r-1", 1L)));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.empty());

    handler.handle(approveEventData());

    verify(adapter, never()).createTicket(any(), any());
    verify(ticketMappingRepository, never()).save(any());
  }

  @Test
  void handle_nonApproveEvent_doesNotCreateTickets() {
    handler.handle(eventData("PAUSE", "IN_PROGRESS", "PAUSED"));

    verify(negotiatorClient, never()).getResources(any(), any());
    verify(adapter, never()).createTicket(any(), any());
  }

  /** Stubs the full happy path: negotiation, resources, and matching org config. */
  private void stubHappyPath(
      EntityModelNegotiationDTO negotiation,
      OrganizationConfig config,
      List<EntityModelResourceWithStatusDTO> resources) {
    stubResources(negotiation, config, resources);
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
  }

  private void stubResources(
      EntityModelNegotiationDTO negotiation,
      OrganizationConfig config,
      List<EntityModelResourceWithStatusDTO> resources) {
    // handleApprove calls registry.findAll() to get fetchConfig, then uses it for client calls
    when(registry.findAll()).thenReturn(List.of(config));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config)).thenReturn(negotiation);
    when(negotiatorClient.getResources(NEGOTIATION_ID, config)).thenReturn(resources);
  }

  private CreateTicketRequest perResourceRequest(
      EntityModelNegotiationDTO negotiation, EntityModelResourceWithStatusDTO resource) {
    return new CreateTicketRequest(
        NegotiationTicketBodyFormatter.buildTitle(DISPLAY_ID, resource.getName()),
        NEGOTIATION_STATE,
        RESOURCE_STATE,
        NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(negotiation, resource));
  }

  private CreateTicketRequest perNegotiationRequest(
      EntityModelNegotiationDTO negotiation, List<EntityModelResourceWithStatusDTO> resources) {
    return new CreateTicketRequest(
        NegotiationTicketBodyFormatter.buildTitle(DISPLAY_ID, null),
        NEGOTIATION_STATE,
        RESOURCE_STATE,
        NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(negotiation, resources));
  }

  private TicketMapping mappingFor(
      CreatedTicket ticket, String resourceKey, EntityModelResourceWithStatusDTO resource) {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, ticket.ticketId(), resourceKey),
        resource.getSourceId(),
        String.valueOf(ORG_INTERNAL_ID),
        ORG_EXT_ID);
  }

  private JsonNode approveEventData() {
    return eventData("APPROVE", "SUBMITTED", "IN_PROGRESS");
  }

  private JsonNode eventData(String event, String fromState, String toState) {
    return objectMapper.valueToTree(
        Map.of(
            "negotiationId", NEGOTIATION_ID,
            "event", event,
            "fromState", fromState,
            "toState", toState));
  }

  private EntityModelNegotiationDTO createNegotiation() {
    return TestUtil.negotiation(NEGOTIATION_ID, DISPLAY_ID, NEGOTIATION_STATE);
  }

  private EntityModelResourceWithStatusDTO createResource(String sourceId, Long id) {
    return TestUtil.resource(id, sourceId, "Resource " + sourceId, ORG_EXT_ID, ORG_INTERNAL_ID);
  }

  private OrganizationConfig createConfig(TicketStrategy strategy) {
    return TestUtil.organizationConfig(ORG_EXT_ID, strategy);
  }
}
