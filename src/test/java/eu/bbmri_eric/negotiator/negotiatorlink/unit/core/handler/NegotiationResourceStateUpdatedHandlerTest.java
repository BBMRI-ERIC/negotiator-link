package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.HandlerResult;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationResourceStateUpdatedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SetStatePayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
class NegotiationResourceStateUpdatedHandlerTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String RESOURCE_ID = "res-1";
  private static final String TICKET_ID = "ticket-42";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String NEW_STATE = "RESOURCE_AVAILABLE";
  private static final String SYSTEM_COMMENT =
      "Resource 'res-1' changed the status to 'Resource Available'";

  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private OrganizationConfigRegistry registry;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private NegotiationResourceStateUpdatedHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new NegotiationResourceStateUpdatedHandler(ticketMappingRepository, registry, objectMapper);
  }

  @Test
  void handle_noMappingForResource_returnsNoIntents() {
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.empty());

    assertThat(handler.handle(eventData(NEW_STATE)).intents()).isEmpty();
  }

  @Test
  void handle_perNegotiation_emitsResourceStateAndSystemComment() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    stubMapping(config);

    HandlerResult result = handler.handle(eventData(NEW_STATE));
    assertThat(result.originatedPost()).isEmpty();

    List<PendingIntent> intents = result.intents();

    assertThat(intents).hasSize(2);
    assertThat(intents)
        .extracting(PendingIntent::outboxIntentType)
        .containsExactly(OutboxIntentType.SET_RESOURCE_STATE, OutboxIntentType.ADD_SYSTEM_COMMENT);
    assertThat(readState(intents.getFirst()).value()).isEqualTo(NEW_STATE);
    assertThat(readSystemComment(intents.getLast()).message()).isEqualTo(SYSTEM_COMMENT);
    assertThat(intents).allSatisfy(i -> assertThat(i.targetId()).isEqualTo(TICKET_ID));
  }

  @Test
  void handle_perResource_emitsResourceStateAndSystemComment() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    stubMapping(config);

    HandlerResult result = handler.handle(eventData(NEW_STATE));
    assertThat(result.originatedPost()).isEmpty();

    List<PendingIntent> intents = result.intents();

    assertThat(intents).hasSize(2);
    assertThat(intents)
        .extracting(PendingIntent::outboxIntentType)
        .containsExactly(OutboxIntentType.SET_RESOURCE_STATE, OutboxIntentType.ADD_SYSTEM_COMMENT);
    assertThat(readSystemComment(intents.getLast()).message()).contains("changed the status to");
    assertThat(readState(intents.getFirst()).value()).isEqualTo(NEW_STATE);
    assertThat(intents).allSatisfy(i -> assertThat(i.targetId()).isEqualTo(TICKET_ID));
  }

  @Test
  void handle_orgNotInConfig_returnsNoIntents() {
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.empty());

    assertThat(handler.handle(eventData(NEW_STATE)).intents()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("incompleteEventCases")
  void handle_incompleteEvent_returnsNoIntents(String caseName, JsonNode data) {
    assertThat(handler.handle(data).intents()).isEmpty();
    verify(ticketMappingRepository, never()).findByIdNegotiationIdAndResourceSourceId(any(), any());
  }

  private static Stream<Arguments> incompleteEventCases() {
    ObjectMapper om = new ObjectMapper();
    return Stream.of(
        Arguments.of(
            "missing resourceId",
            om.valueToTree(Map.of("negotiationId", NEGOTIATION_ID, "toState", NEW_STATE))),
        Arguments.of(
            "missing negotiationId",
            om.valueToTree(Map.of("resourceId", RESOURCE_ID, "toState", NEW_STATE))),
        Arguments.of(
            "missing toState",
            om.valueToTree(Map.of("negotiationId", NEGOTIATION_ID, "resourceId", RESOURCE_ID))));
  }

  @Test
  void handle_resourceStateHasTicketMapping_emitsResourceStateSystemCommentThenTicketState() {
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of(NEW_STATE, "closed"));
    stubMapping(config);

    List<PendingIntent> intents = handler.handle(eventData(NEW_STATE)).intents();

    assertThat(intents)
        .extracting(PendingIntent::outboxIntentType)
        .containsExactly(
            OutboxIntentType.SET_RESOURCE_STATE,
            OutboxIntentType.ADD_SYSTEM_COMMENT,
            OutboxIntentType.SET_TICKET_STATE);
    assertThat(readState(intents.get(2)).value()).isEqualTo("closed");
  }

  @Test
  void handle_resourceStateHasNoTicketMapping_doesNotEmitTicketState() {
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of("OTHER_STATE", "closed"));
    stubMapping(config);

    List<PendingIntent> intents = handler.handle(eventData(NEW_STATE)).intents();

    assertThat(intents)
        .extracting(PendingIntent::outboxIntentType)
        .doesNotContain(OutboxIntentType.SET_TICKET_STATE);
  }

  private void stubMapping(OrganizationConfig config) {
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
  }

  private SetStatePayload readState(PendingIntent intent) {
    try {
      return objectMapper.readValue(intent.payloadJson(), SetStatePayload.class);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private AddSystemCommentPayload readSystemComment(PendingIntent intent) {
    try {
      return objectMapper.readValue(intent.payloadJson(), AddSystemCommentPayload.class);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
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

  private OrganizationConfig createConfig(
      TicketStrategy strategy, Map<String, String> resourceStateToTicketState) {
    return TestUtil.organizationConfig(ORG_EXT_ID, strategy, Map.of(), resourceStateToTicketState);
  }
}
