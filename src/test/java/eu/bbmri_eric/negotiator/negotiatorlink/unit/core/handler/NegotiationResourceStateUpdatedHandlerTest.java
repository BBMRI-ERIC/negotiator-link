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
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedTransition;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.handler.NegotiationResourceStateUpdatedHandler;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.IntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.AddSystemCommentPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SetStatePayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
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
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String NEW_STATE = "RESOURCE_AVAILABLE";
  private static final String SYSTEM_COMMENT =
      "Resource 'res-1' changed the status to 'Resource Available'";

  @Mock private TicketMappingRepository ticketMappingRepository;
  @Mock private OriginatedTransitionRepository originatedTransitionRepository;
  @Mock private OrganizationConfigRegistry registry;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private NegotiationResourceStateUpdatedHandler handler;

  @BeforeEach
  void setUp() {
    handler =
        new NegotiationResourceStateUpdatedHandler(
            ticketMappingRepository, originatedTransitionRepository, registry, objectMapper);
  }

  @Test
  void handle_noMappingForResource_returnsNoIntents() {
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.empty());

    assertThat(handler.handle(eventData(NEW_STATE))).isEmpty();
  }

  @Test
  void handle_noOriginatedTransition_emitsResourceStateAndSystemComment() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    stubMapping(config);
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());

    List<PendingIntent> intents = handler.handle(eventData(NEW_STATE));

    assertThat(intents).hasSize(2);
    assertThat(intents)
        .extracting(PendingIntent::intentType)
        .containsExactly(IntentType.SET_RESOURCE_STATE, IntentType.ADD_SYSTEM_COMMENT);
    assertThat(readState(intents.getFirst()).value()).isEqualTo(NEW_STATE);
    assertThat(readSystemComment(intents.getLast()).message()).isEqualTo(SYSTEM_COMMENT);
    assertThat(intents).allSatisfy(i -> assertThat(i.targetId()).isEqualTo(TICKET_ID));
  }

  @Test
  void handle_perResource_emitsResourceStateAndSystemComment() {
    var config = createConfig(TicketStrategy.PER_RESOURCE);
    stubMapping(config);
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());

    List<PendingIntent> intents = handler.handle(eventData(NEW_STATE));

    assertThat(intents).hasSize(2);
    assertThat(intents)
        .extracting(PendingIntent::intentType)
        .containsExactly(IntentType.SET_RESOURCE_STATE, IntentType.ADD_SYSTEM_COMMENT);
    assertThat(readSystemComment(intents.getLast()).message()).contains("changed the status to");
    assertThat(readState(intents.getFirst()).value()).isEqualTo(NEW_STATE);
    assertThat(intents).allSatisfy(i -> assertThat(i.targetId()).isEqualTo(TICKET_ID));
  }

  @Test
  void handle_originatedTransition_firstWebhook_emitsStateAndKeepsRow() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var transition = transition(2, 0);
    stubMapping(config);
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.of(transition));
    when(originatedTransitionRepository.findByIdForUpdate(transition.getRowId()))
        .thenReturn(Optional.of(transition));

    List<PendingIntent> intents = handler.handle(eventData(NEW_STATE));

    verify(originatedTransitionRepository).save(transition);
    assertThat(intents).hasSize(2);
    assertThat(intents)
        .extracting(PendingIntent::intentType)
        .containsExactly(IntentType.SET_RESOURCE_STATE, IntentType.ADD_SYSTEM_COMMENT);
    assertThat(intents).allSatisfy(i -> assertThat(i.targetId()).isEqualTo(TICKET_ID));
    assertThat(readSystemComment(intents.getLast()).message()).contains("changed the status to");
    assertThat(readState(intents.getFirst()).value()).isEqualTo(NEW_STATE);
    verify(originatedTransitionRepository, never()).deleteById(any());
  }

  @Test
  void handle_originatedTransition_secondWebhook_discardedAndRowDeleted() {
    var config = createConfig(TicketStrategy.PER_NEGOTIATION);
    var transition = transition(2, 1);
    stubMapping(config);
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.of(transition));
    when(originatedTransitionRepository.findByIdForUpdate(transition.getRowId()))
        .thenReturn(Optional.of(transition));

    List<PendingIntent> intents = handler.handle(eventData(NEW_STATE));

    verify(originatedTransitionRepository).save(transition);
    assertThat(intents).hasSize(1);
    var intent = intents.getFirst();
    assertThat(intent.intentType()).isEqualTo(IntentType.ADD_SYSTEM_COMMENT);
    assertThat(readSystemComment(intents.getFirst()).message()).contains("changed the status to");
    verify(originatedTransitionRepository).deleteById(transition.getRowId());
  }

  @Test
  void handle_orgNotInConfig_returnsNoIntents() {
    when(ticketMappingRepository.findByIdNegotiationIdAndResourceSourceId(
            NEGOTIATION_ID, RESOURCE_ID))
        .thenReturn(Optional.of(mapping()));
    when(registry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.empty());

    assertThat(handler.handle(eventData(NEW_STATE))).isEmpty();
  }

  @Test
  void handle_incompleteEvent_returnsNoIntents() {
    JsonNode data =
        objectMapper.valueToTree(Map.of("negotiationId", NEGOTIATION_ID, "toState", NEW_STATE));

    assertThat(handler.handle(data)).isEmpty();
    verify(ticketMappingRepository, never()).findByIdNegotiationIdAndResourceSourceId(any(), any());
  }

  @Test
  void handle_resourceStateHasTicketMapping_emitsResourceStateSystemCommentThenTicketState() {
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of(NEW_STATE, "closed"));
    stubMapping(config);
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());

    List<PendingIntent> intents = handler.handle(eventData(NEW_STATE));

    assertThat(intents)
        .extracting(PendingIntent::intentType)
        .containsExactly(
            IntentType.SET_RESOURCE_STATE,
            IntentType.ADD_SYSTEM_COMMENT,
            IntentType.SET_TICKET_STATE);
    assertThat(readState(intents.get(2)).value()).isEqualTo("closed");
  }

  @Test
  void handle_resourceStateHasNoTicketMapping_doesNotEmitTicketState() {
    var config = createConfig(TicketStrategy.PER_RESOURCE, Map.of("OTHER_STATE", "closed"));
    stubMapping(config);
    when(originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
            NEGOTIATION_ID, TICKET_ID, NEW_STATE))
        .thenReturn(Optional.empty());

    List<PendingIntent> intents = handler.handle(eventData(NEW_STATE));

    assertThat(intents)
        .extracting(PendingIntent::intentType)
        .doesNotContain(IntentType.SET_TICKET_STATE);
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

  private OriginatedTransition transition(int expected, int received) {
    var t = new OriginatedTransition(NEGOTIATION_ID, TICKET_ID, expected, NEW_STATE);
    t.setReceivedCount(received);
    t.setRowId(1L);
    return t;
  }
}
