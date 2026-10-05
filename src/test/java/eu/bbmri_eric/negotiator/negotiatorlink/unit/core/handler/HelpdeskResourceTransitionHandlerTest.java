package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.organizationConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HelpdeskResourceTransitionHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.ResourceTransitionEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.IntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendBulkResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SetStatePayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService;
import eu.bbmri_eric.negotiator.negotiatorlink.util.NegotiatorResourceEvent;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
  @Mock private NegotiatorClient negotiatorClient;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private OrganizationConfig config;
  private HelpdeskResourceTransitionHandler handler;

  @BeforeEach
  void setUp() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    ResourceTransitionService resourceTransitionService =
        new ResourceTransitionService(negotiatorClient, resourceEventsConfig());
    handler =
        new HelpdeskResourceTransitionHandler(ticketMappingRepository, resourceTransitionService);
  }

  @Test
  void handle_perResource_validTransition_emitsSendResourceEventIntent() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping(RESOURCE_ID, RESOURCE_SOURCE_ID)));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of(REQUESTED_EVENT, "STEP_AWAY"));

    List<PendingIntent> intents = handler.handle(callbackEvent(), config);

    assertThat(intents).hasSize(1);
    PendingIntent intent = intents.getFirst();
    assertThat(intent.intentType()).isEqualTo(IntentType.SEND_RESOURCE_EVENT);
    assertThat(intent.targetKind()).isEqualTo(TargetKind.NEGOTIATION);
    SendResourceEventPayload payload = read(intent, SendResourceEventPayload.class);
    assertThat(payload.resourceSourceId()).isEqualTo(RESOURCE_SOURCE_ID);
    assertThat(payload.event()).isEqualTo(REQUESTED_EVENT);
    verify(negotiatorClient, never()).sendResourceEvent(any(), any(), any(), any());
  }

  @Test
  void handle_perResource_invalidTransition_emitsSetResourceStateAndSystemCommentIntents() {
    when(ticketMappingRepository.findByIdHelpdeskTicketIdAndOrganizationExtId(
            TICKET_ID, ORG_EXT_ID))
        .thenReturn(List.of(mapping(RESOURCE_ID, RESOURCE_SOURCE_ID)));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of("STEP_AWAY"));

    List<PendingIntent> intents = handler.handle(callbackEvent(), config);

    assertThat(intents)
        .extracting(PendingIntent::intentType)
        .containsExactly(IntentType.SET_RESOURCE_STATE, IntentType.ADD_SYSTEM_COMMENT);
    assertThat(intents).allSatisfy(i -> assertThat(i.targetId()).isEqualTo(TICKET_ID));
    SetStatePayload resetState = read(intents.getFirst(), SetStatePayload.class);
    assertThat(resetState.field()).isEqualTo(SetStatePayload.FIELD_RESOURCE_STATE);
    assertThat(resetState.value()).isEqualTo(RESOURCE_STATE);
    AddSystemCommentPayload comment = read(intents.getLast(), AddSystemCommentPayload.class);
    assertThat(comment.message()).isNotBlank();
    verify(negotiatorClient, never()).sendResourceEvent(any(), any(), any(), any());
  }

  @Test
  void handle_perNegotiation_allValid_emitsBulkIntent() {
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

    List<PendingIntent> intents = handler.handle(callbackEvent(), config);

    assertThat(intents).hasSize(1);
    PendingIntent intent = intents.getFirst();
    assertThat(intent.intentType()).isEqualTo(IntentType.SEND_BULK_RESOURCE_EVENT);
    SendBulkResourceEventPayload payload = read(intent, SendBulkResourceEventPayload.class);
    assertThat(payload.resourceIds()).containsExactly(101L, 102L);
    assertThat(payload.targetState())
        .isEqualTo(NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.targetState());
    verify(negotiatorClient, never()).sendBulkResourceEvent(any(), any(), any(), any());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("perNegotiationRejectionCases")
  void handle_perNegotiation_invalidTransition_emitsSetResourceStateAndSystemCommentIntents(
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

    List<PendingIntent> intents = handler.handle(callbackEvent(), config);

    assertThat(intents)
        .extracting(PendingIntent::intentType)
        .containsExactly(IntentType.SET_RESOURCE_STATE, IntentType.ADD_SYSTEM_COMMENT);
    SetStatePayload resetState = read(intents.getFirst(), SetStatePayload.class);
    assertThat(resetState.field()).isEqualTo(SetStatePayload.FIELD_RESOURCE_STATE);
    assertThat(resetState.value()).isEqualTo(RESOURCE_STATE);
    AddSystemCommentPayload comment = read(intents.getLast(), AddSystemCommentPayload.class);
    assertThat(comment.message().contains(RESOURCE_SOURCE_ID_2))
        .isEqualTo(commentShouldMentionResource2);
    verify(negotiatorClient, never()).sendBulkResourceEvent(any(), any(), any(), any());
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

  private <T> T read(PendingIntent intent, Class<T> type) {
    try {
      return objectMapper.readValue(intent.payloadJson(), type);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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

  private static NegotiatorLinkProperties resourceEventsConfig() {
    NegotiatorLinkProperties props = new NegotiatorLinkProperties();
    props.setResourceEvents(NegotiatorResourceEvent.asMap());
    return props;
  }
}
