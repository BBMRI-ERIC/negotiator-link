package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpDeskAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationResourceStateUpdatedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedTransition;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedTransitionRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
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
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String NEW_STATE = "RESOURCE_AVAILABLE";

  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private OriginatedTransitionRepository originatedTransitionRepository;
  @Mock private OrganizationConfigRegistry registry;
  @Mock private HelpDeskAdapter adapter;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private NegotiationResourceStateUpdatedHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new NegotiationResourceStateUpdatedHandler(
            ticketMappingRepository,
            originatedTransitionRepository,
            registry,
            Map.of(HelpDeskType.ZAMMAD, adapter),
            objectMapper);
  }

  @Test
  void handle_noMappingForResource_doesNothing() {
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.empty());

    handler.handle(eventData(NEW_STATE));

    verify(adapter, never()).updateResourceState(any(), any(), any());
  }

  @Test
  void handle_noOriginatedTransition_updatesDirectly() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketId(NEGOTIATION_ID, TICKET_ID))
        .thenReturn(Optional.empty());

    handler.handle(eventData(NEW_STATE));

    verify(adapter).updateResourceState(eq(TICKET_ID), eq(NEW_STATE), eq(config));
  }

  @Test
  void handle_perResource_updatesDirectly() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketId(NEGOTIATION_ID, TICKET_ID))
        .thenReturn(Optional.empty());

    handler.handle(eventData(NEW_STATE));

    verify(adapter).updateResourceState(eq(TICKET_ID), eq(NEW_STATE), eq(config));
  }

  @Test
  void handle_originatedTransition_firstWebhook_updatesStateAndKeepsRow() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var originated = transition(2, 0);
    var afterIncrement = transition(2, 1);

    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketId(NEGOTIATION_ID, TICKET_ID))
        .thenReturn(Optional.of(originated));
    when(originatedTransitionRepository.findById(originated.getRowId()))
        .thenReturn(Optional.of(afterIncrement));

    handler.handle(eventData(NEW_STATE));

    verify(originatedTransitionRepository).incrementReceivedCount(eq(originated.getRowId()));
    verify(adapter).updateResourceState(eq(TICKET_ID), eq(NEW_STATE), eq(config));
    verify(originatedTransitionRepository, never()).delete(any());
  }

  @Test
  void handle_originatedTransition_secondWebhook_discardedAndRowDeleted() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var originated = transition(2, 1);
    var afterIncrement = transition(2, 2);

    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(originatedTransitionRepository.findByNegotiationIdAndTicketId(NEGOTIATION_ID, TICKET_ID))
        .thenReturn(Optional.of(originated));
    when(originatedTransitionRepository.findById(originated.getRowId()))
        .thenReturn(Optional.of(afterIncrement));

    handler.handle(eventData(NEW_STATE));

    verify(originatedTransitionRepository).incrementReceivedCount(eq(originated.getRowId()));
    verify(adapter, never()).updateResourceState(any(), any(), any());
    verify(originatedTransitionRepository).delete(eq(afterIncrement));
  }

  @Test
  void handle_incompleteEvent_doesNothing() {
    JsonNode data =
        objectMapper.valueToTree(Map.of("negotiationId", NEGOTIATION_ID, "toState", NEW_STATE));

    handler.handle(data);

    verify(ticketMappingRepository, never()).findByIdNegotiationIdAndResourceSourceId(any(), any());
    verify(adapter, never()).updateResourceState(any(), any(), any());
  }

  private JsonNode eventData(String toState) {
    return objectMapper.valueToTree(
        Map.of(
            "negotiationId", NEGOTIATION_ID,
            "resourceId", RESOURCE_ID,
            "fromState", "SUBMITTED",
            "toState", toState,
            "event", "MARK_AS_AVAILABLE"));
  }

  private TicketMapping mapping() {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, TICKET_ID, RESOURCE_ID), "src-1", "100", ORG_EXT_ID);
  }

  private OrganizationConfig createConfig(TicketStrategy strategy) {
    return TestUtil.organizationConfig(ORG_EXT_ID, strategy);
  }

  private OriginatedTransition transition(int expected, int received) {
    var t = new OriginatedTransition(NEGOTIATION_ID, TICKET_ID, expected, NEW_STATE);
    t.setReceivedCount(received);
    t.setRowId(1L);
    return t;
  }
}
