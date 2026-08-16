package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.organizationConfig;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpDeskAdapter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpDeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HelpdeskResourceTransitionHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedTransition;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.OriginatedTransitionRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HelpdeskResourceTransitionHandlerTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String TICKET_ID = "ticket-42";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String RESOURCE_ID = "101";
  private static final String RESOURCE_SOURCE_ID = "biobank:DE:MHH:collection1";
  private static final String RESOURCE_SOURCE_ID_2 = "biobank:DE:MHH:collection2";
  private static final String REQUESTED_EVENT = "MARK_AS_CHECKING_AVAILABILITY";
  private static final String HELPDESK_ACTOR = "Jane Rep";

  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private OriginatedTransitionRepository originatedTransitionRepository;
  @Mock private NegotiatorClient negotiatorClient;
  @Mock private HelpDeskAdapter adapter;

  private OrganizationConfig config;
  private HelpdeskResourceTransitionHandler handler;

  @BeforeEach
  void setUp() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    handler =
        new HelpdeskResourceTransitionHandler(
            ticketMappingRepository,
            originatedTransitionRepository,
            negotiatorClient,
            Map.of(HelpDeskType.ZAMMAD, adapter));
  }

  @Test
  void handle_perResource_validTransition_sendsResourceEvent() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping(RESOURCE_ID, RESOURCE_SOURCE_ID)));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of(REQUESTED_EVENT, "STEP_AWAY"));

    handler.handle(callbackEvent(), config);

    verify(negotiatorClient)
        .sendResourceEvent(
            eq(NEGOTIATION_ID),
            eq(RESOURCE_SOURCE_ID),
            eq(REQUESTED_EVENT),
            eq(HELPDESK_ACTOR),
            eq(config));
    verify(adapter, never()).updateResourceState(any(), any(), any());
    verify(adapter, never()).addSystemComment(any(), any(), any());
  }

  @Test
  void handle_perResource_invalidTransition_clearsFieldAndPostsSystemComment() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping(RESOURCE_ID, RESOURCE_SOURCE_ID)));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of("STEP_AWAY"));

    handler.handle(callbackEvent(), config);

    verify(adapter).updateResourceState(eq(TICKET_ID), eq(null), eq(config));
    verify(adapter).addSystemComment(eq(TICKET_ID), any(String.class), eq(config));
    verify(negotiatorClient, never()).sendResourceEvent(any(), any(), any(), any(), any());
  }

  @Test
  void handle_perResource_ticketNotFound_makesNoAdapterCalls() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of());

    handler.handle(callbackEvent(), config);

    verify(negotiatorClient, never()).sendResourceEvent(any(), any(), any(), any(), any());
    verify(adapter, never()).updateResourceState(any(), any(), any());
    verify(adapter, never()).addSystemComment(any(), any(), any());
  }

  @Test
  void handle_perNegotiation_allValid_savesOriginatedTransitionAndSendsBulk() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_NEGOTIATION);

    var mapping1 = mapping("101", RESOURCE_SOURCE_ID);
    var mapping2 = mapping("102", RESOURCE_SOURCE_ID_2);

    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping1, mapping2));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of(REQUESTED_EVENT));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID_2, config))
        .thenReturn(Set.of(REQUESTED_EVENT));

    handler.handle(callbackEvent(), config);

    var captor = ArgumentCaptor.forClass(OriginatedTransition.class);
    verify(originatedTransitionRepository).save(captor.capture());
    var saved = captor.getValue();
    assert saved.getNegotiationId().equals(NEGOTIATION_ID);
    assert saved.getTicketId().equals(TICKET_ID);
    assert saved.getExpectedCount() == 2;
    assert saved.getNewState().equals(REQUESTED_EVENT);

    verify(negotiatorClient)
        .sendBulkResourceEvent(
            eq(NEGOTIATION_ID),
            eq(List.of(101L, 102L)),
            eq(ResourceTransitionEvent.MARK_AS_CHECKING_AVAILABILITY.targetState()),
            eq(HELPDESK_ACTOR),
            eq(config));
    verify(adapter, never()).updateResourceState(any(), any(), any());
    verify(adapter, never()).addSystemComment(any(), any(), any());
  }

  @Test
  void handle_perNegotiation_oneResourceInvalid_clearsFieldAndPostsSystemComment() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_NEGOTIATION);

    var mapping1 = mapping("101", RESOURCE_SOURCE_ID);
    var mapping2 = mapping("102", RESOURCE_SOURCE_ID_2);

    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping1, mapping2));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of(REQUESTED_EVENT));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID_2, config))
        .thenReturn(Set.of("STEP_AWAY"));

    handler.handle(callbackEvent(), config);

    verify(adapter).updateResourceState(eq(TICKET_ID), eq(null), eq(config));
    verify(adapter).addSystemComment(eq(TICKET_ID), any(String.class), eq(config));
    verify(negotiatorClient, never()).sendBulkResourceEvent(any(), any(), any(), any(), any());
    verify(originatedTransitionRepository, never()).save(any());
  }

  @Test
  void handle_perNegotiation_unknownEvent_passesThroughRawAsState() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_NEGOTIATION);
    String unknownEvent = "SOME_UNKNOWN_EVENT";

    var mapping1 = mapping("101", RESOURCE_SOURCE_ID);
    var mapping2 = mapping("102", RESOURCE_SOURCE_ID_2);

    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping1, mapping2));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of(unknownEvent));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID_2, config))
        .thenReturn(Set.of(unknownEvent));

    handler.handle(
        new HelpdeskCallbackEvent(
            TICKET_ID,
            HelpdeskCallbackType.RESOURCE_TRANSITION,
            unknownEvent,
            null,
            HELPDESK_ACTOR),
        config);

    verify(negotiatorClient)
        .sendBulkResourceEvent(
            eq(NEGOTIATION_ID), any(), eq(unknownEvent), eq(HELPDESK_ACTOR), eq(config));
  }

  private TicketMapping mapping(String resourceId, String resourceSourceId) {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, TICKET_ID, resourceId),
        resourceSourceId,
        "org-1",
        ORG_EXT_ID);
  }

  private HelpdeskCallbackEvent callbackEvent() {
    return new HelpdeskCallbackEvent(
        TICKET_ID, HelpdeskCallbackType.RESOURCE_TRANSITION, REQUESTED_EVENT, null, HELPDESK_ACTOR);
  }
}
