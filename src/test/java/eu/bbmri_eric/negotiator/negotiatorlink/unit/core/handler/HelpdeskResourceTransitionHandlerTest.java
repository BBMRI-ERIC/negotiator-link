package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.organizationConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedTransition;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HelpdeskResourceTransitionHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.NegotiatorResourceEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
  private static final String RESOURCE_STATE = "IN_PROGRESS";

  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private OriginatedTransitionRepository originatedTransitionRepository;
  @Mock private NegotiatorClient negotiatorClient;
  @Mock private HelpdeskClient helpdeskClient;

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
            Map.of(HelpdeskType.ZAMMAD, helpdeskClient));
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
            eq(NEGOTIATION_ID), eq(RESOURCE_SOURCE_ID), eq(REQUESTED_EVENT), eq(config));
    verify(helpdeskClient, never()).updateResourceState(any(), any(), any());
    verify(helpdeskClient, never()).addSystemComment(any(), any(), any());
  }

  @Test
  void handle_perResource_invalidTransition_clearsFieldAndPostsSystemComment() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping(RESOURCE_ID, RESOURCE_SOURCE_ID)));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of("STEP_AWAY"));

    handler.handle(callbackEvent(), config);

    verify(helpdeskClient).updateResourceState(eq(TICKET_ID), eq(RESOURCE_STATE), eq(config));
    verify(helpdeskClient).addSystemComment(eq(TICKET_ID), any(String.class), eq(config));
    verify(negotiatorClient, never()).sendResourceEvent(any(), any(), any(), any());
  }

  @Test
  void handle_perResource_ticketNotFound_makesNoAdapterCalls() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of());

    handler.handle(callbackEvent(), config);

    verify(negotiatorClient, never()).sendResourceEvent(any(), any(), any(), any());
    verify(helpdeskClient, never()).updateResourceState(any(), any(), any());
    verify(helpdeskClient, never()).addSystemComment(any(), any(), any());
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
    assertThat(saved.getNegotiationId()).isEqualTo(NEGOTIATION_ID);
    assertThat(saved.getTicketId()).isEqualTo(TICKET_ID);
    assertThat(saved.getExpectedCount()).isEqualTo(2);
    assertThat(saved.getNewState()).isEqualTo("CHECKING_AVAILABILITY");

    verify(negotiatorClient)
        .sendBulkResourceEvent(
            eq(NEGOTIATION_ID),
            eq(List.of(101L, 102L)),
            eq(NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.targetState()),
            eq(config));
    verify(helpdeskClient, never()).updateResourceState(any(), any(), any());
    verify(helpdeskClient, never()).addSystemComment(any(), any(), any());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("perNegotiationRejectionCases")
  void handle_perNegotiation_invalidTransition_clearsFieldAndPostsSystemComment(
      String caseName,
      Set<String> resource1Events,
      Set<String> resource2Events,
      boolean commentShouldMentionResource2) {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_NEGOTIATION);

    var mapping1 = mapping("101", RESOURCE_SOURCE_ID);
    var mapping2 = mapping("102", RESOURCE_SOURCE_ID_2);

    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping1, mapping2));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(resource1Events);
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID_2, config))
        .thenReturn(resource2Events);

    handler.handle(callbackEvent(), config);

    verify(helpdeskClient).updateResourceState(eq(TICKET_ID), eq(RESOURCE_STATE), eq(config));
    verify(helpdeskClient)
        .addSystemComment(
            eq(TICKET_ID),
            argThat(s -> s.contains(RESOURCE_SOURCE_ID_2) == commentShouldMentionResource2),
            eq(config));
    verify(negotiatorClient, never()).sendBulkResourceEvent(any(), any(), any(), any());
    verify(originatedTransitionRepository, never()).save(any());
  }

  private static Stream<Arguments> perNegotiationRejectionCases() {
    return Stream.of(
        arguments("one resource invalid", Set.of(REQUESTED_EVENT), Set.of("STEP_AWAY"), true),
        arguments(
            "all resources invalid with different allowed events",
            Set.of("MARK_AS_AVAILABLE"),
            Set.of("STEP_AWAY"),
            true),
        arguments(
            "all resources invalid with same allowed events",
            Set.of("STEP_AWAY"),
            Set.of("STEP_AWAY"),
            false));
  }

  private TicketMapping mapping(String resourceId, String resourceSourceId) {
    return new TicketMapping(
        new TicketMappingId(NEGOTIATION_ID, TICKET_ID, resourceId),
        resourceSourceId,
        "org-1",
        ORG_EXT_ID);
  }

  private ResourceTransitionEvent callbackEvent() {
    return new ResourceTransitionEvent(TICKET_ID, RESOURCE_STATE, REQUESTED_EVENT);
  }
}
