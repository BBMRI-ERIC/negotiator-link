package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedTransition;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationResourceStateUpdatedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.TimelinePropagationService;
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
class NegotiationResourceStateUpdatedHandlerTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String RESOURCE_ID = "res-1";
  private static final String TICKET_ID = "ticket-42";
  private static final String SECOND_TICKET_ID = "ticket-43";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String OTHER_ORG_EXT_ID = "bbmri:CZ:MMCI";
  private static final String NEW_STATE = "RESOURCE_AVAILABLE";

  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private OriginatedTransitionRepository originatedTransitionRepository;
  @Mock private OrganizationConfigRegistry registry;
  @Mock private HelpdeskClient helpdeskClient;
  @Mock private TimelinePropagationService timelinePropagationService;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private NegotiationResourceStateUpdatedHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new NegotiationResourceStateUpdatedHandler(
            ticketMappingRepository,
            originatedTransitionRepository,
            registry,
            Map.of(HelpdeskType.ZAMMAD, helpdeskClient),
            objectMapper,
            timelinePropagationService);
  }

  @Test
  void handle_noMappingForResource_doesNothing() {
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.empty());

    handler.handle(eventData());

    verify(helpdeskClient, never()).updateResourceState(any(), any(), any());
    verify(timelinePropagationService, never()).propagate(any(), any(), any());
  }

  @Test
  void handle_noOriginatedTransition_updatesDirectly() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());

    handler.handle(eventData());

    verify(helpdeskClient).updateResourceState(eq(TICKET_ID), eq(NEW_STATE), eq(config));
  }

  @Test
  void handle_perResource_updatesDirectly() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());

    handler.handle(eventData());

    verify(helpdeskClient).updateResourceState(eq(TICKET_ID), eq(NEW_STATE), eq(config));
  }

  @Test
  void handle_originatedTransition_firstWebhook_appliedAndCountIncremented() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var transition = transition(2, 0);

    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.of(transition));
    when(originatedTransitionRepository.findByIdForUpdate(transition.getRowId()))
        .thenReturn(Optional.of(transition));

    handler.handle(eventData());

    verify(originatedTransitionRepository).save(transition);
    verify(helpdeskClient).updateResourceState(eq(TICKET_ID), eq(NEW_STATE), eq(config));
    verify(originatedTransitionRepository, never()).deleteById(any());
  }

  @Test
  void handle_originatedTransition_secondWebhook_discardedAndRowDeleted() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var transition = transition(2, 1);

    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.of(transition));
    when(originatedTransitionRepository.findByIdForUpdate(transition.getRowId()))
        .thenReturn(Optional.of(transition));

    handler.handle(eventData());

    verify(originatedTransitionRepository).save(transition);
    verify(helpdeskClient, never()).updateResourceState(any(), any(), any());
    verify(originatedTransitionRepository).deleteById(eq(transition.getRowId()));
  }

  @Test
  void handle_orgNotInConfig_doesNothing() {
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.empty());

    handler.handle(eventData());

    verify(helpdeskClient, never()).updateResourceState(any(), any(), any());
    verify(timelinePropagationService, never()).propagate(any(), any(), any());
  }

  @Test
  void handle_noAdapterForType_doesNothing() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));

    var handlerWithNoAdapters =
        new NegotiationResourceStateUpdatedHandler(
            ticketMappingRepository,
            originatedTransitionRepository,
            registry,
            Map.of(),
            objectMapper,
            timelinePropagationService);

    handlerWithNoAdapters.handle(eventData());

    verify(helpdeskClient, never()).updateResourceState(any(), any(), any());
  }

  @Test
  void handle_adapterThrows_doesNotPropagateException() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());
    doThrow(new RuntimeException("adapter down"))
        .when(helpdeskClient)
        .updateResourceState(any(), any(), any());

    assertDoesNotThrow(() -> handler.handle(eventData()));
    verify(helpdeskClient).updateResourceState(eq(TICKET_ID), eq(NEW_STATE), eq(config));
  }

  @Test
  void handle_incompleteEvent_doesNothing() {
    JsonNode data =
        objectMapper.valueToTree(Map.of("negotiationId", NEGOTIATION_ID, "toState", NEW_STATE));

    handler.handle(data);

    verify(ticketMappingRepository, never()).findByIdNegotiationIdAndResourceSourceId(any(), any());
    verify(helpdeskClient, never()).updateResourceState(any(), any(), any());
    verify(timelinePropagationService, never()).propagate(any(), any(), any());
  }

  @Test
  void handle_resourceStateHasTicketMapping_updatesResourceStateThenTicketState() {
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of(NEW_STATE, "closed"));
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());

    handler.handle(eventData(NEW_STATE));

    var order = inOrder(helpdeskClient);
    order.verify(helpdeskClient).updateResourceState(eq(TICKET_ID), eq(NEW_STATE), eq(config));
    order.verify(helpdeskClient).updateTicketState(eq(TICKET_ID), eq("closed"), eq(config));
  }

  @Test
  void handle_resourceStateHasNoTicketMapping_doesNotUpdateTicketState() {
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of("OTHER_STATE", "closed"));
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());

    handler.handle(eventData(NEW_STATE));

    verify(helpdeskClient).updateResourceState(eq(TICKET_ID), eq(NEW_STATE), eq(config));
    verify(helpdeskClient, never()).updateTicketState(any(), any(), any());
  }

  @Test
  void handle_updateTicketStateThrows_doesNotPropagateException() {
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of(NEW_STATE, "closed"));
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());
    doThrow(new RuntimeException("adapter down"))
        .when(helpdeskClient)
        .updateTicketState(any(), any(), any());

    assertDoesNotThrow(() -> handler.handle(eventData(NEW_STATE)));
    verify(helpdeskClient).updateTicketState(eq(TICKET_ID), eq("closed"), eq(config));
  }

  @Test
  void handle_multipleTicketsForNegotiation_propagatesTimelineToEachTicket() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(
            List.of(
                mapping(TICKET_ID, RESOURCE_ID, ORG_EXT_ID),
                mapping(SECOND_TICKET_ID, "res-2", ORG_EXT_ID)));

    handler.handle(eventData());

    verify(helpdeskClient).updateResourceState(eq(TICKET_ID), eq(NEW_STATE), eq(config));
    verify(timelinePropagationService).propagate(eq(NEGOTIATION_ID), eq(TICKET_ID), eq(config));
    verify(timelinePropagationService)
        .propagate(eq(NEGOTIATION_ID), eq(SECOND_TICKET_ID), eq(config));
  }

  @Test
  void handle_duplicateTicketRows_propagatesOncePerTicket() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(
            List.of(
                mapping(TICKET_ID, RESOURCE_ID, ORG_EXT_ID),
                mapping(TICKET_ID, "res-2", ORG_EXT_ID)));

    handler.handle(eventData());

    verify(timelinePropagationService, times(1))
        .propagate(eq(NEGOTIATION_ID), eq(TICKET_ID), eq(config));
  }

  @Test
  void handle_resourceOrgNotOptedIn_stillPropagatesTimelineToMappedTickets() {
    var optedInConfig = TestUtil.organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.empty());
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(optedInConfig));
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(List.of(mapping(TICKET_ID, "res-2", ORG_EXT_ID)));

    handler.handle(eventData());

    verify(helpdeskClient, never()).updateResourceState(any(), any(), any());
    verify(timelinePropagationService)
        .propagate(eq(NEGOTIATION_ID), eq(TICKET_ID), eq(optedInConfig));
  }

  @Test
  void handle_mappedTicketOrgNotInRegistry_skipsThatTicket() {
    var optedInConfig = TestUtil.organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.empty());
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(optedInConfig));
    when(registry.findByExternalId(OTHER_ORG_EXT_ID)).thenReturn(Optional.empty());
    when(ticketMappingRepository.findByIdNegotiationId(NEGOTIATION_ID))
        .thenReturn(
            List.of(
                mapping(TICKET_ID, RESOURCE_ID, ORG_EXT_ID),
                mapping(SECOND_TICKET_ID, "res-2", OTHER_ORG_EXT_ID)));

    handler.handle(eventData());

    verify(timelinePropagationService)
        .propagate(eq(NEGOTIATION_ID), eq(TICKET_ID), eq(optedInConfig));
    verify(timelinePropagationService, never())
        .propagate(eq(NEGOTIATION_ID), eq(SECOND_TICKET_ID), any());
  }

  private JsonNode eventData() {
    return objectMapper.valueToTree(
        Map.of(
            "negotiationId", NEGOTIATION_ID,
            "resourceId", RESOURCE_ID,
            "fromState", "CHECKING_AVAILABILITY",
            "toState", NEW_STATE,
            "event", "MARK_AS_AVAILABLE"));
  }

  private TicketMapping mapping() {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, TICKET_ID, RESOURCE_ID), "src-1", "100", ORG_EXT_ID);
  }

  private TicketMapping mapping(String ticketId, String resourceId, String orgExtId) {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, ticketId, resourceId), "src-1", "100", orgExtId);
  }

  private OrganizationConfig createConfig(TicketStrategy strategy) {
    return TestUtil.organizationConfig(ORG_EXT_ID, strategy);
  }

  private OrganizationConfig createConfig(
      TicketStrategy strategy, Map<String, String> resourceStateToTicketState) {
    return TestUtil.organizationConfig(ORG_EXT_ID, strategy, Map.of(), resourceStateToTicketState);
  }

  private OriginatedTransition transition(int expected, int received) {
    var t = new OriginatedTransition(NEGOTIATION_ID, TICKET_ID, expected, NEW_STATE);
    t.setReceivedCount(received);
    t.setRowId(1L);
    return t;
  }
}
