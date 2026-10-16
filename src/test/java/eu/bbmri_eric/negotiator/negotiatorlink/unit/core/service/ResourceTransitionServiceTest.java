package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.service;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.organizationConfig;
import static org.assertj.core.api.Assertions.assertThat;
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
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendBulkResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService.InvalidResource;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService.TransitionResult;
import eu.bbmri_eric.negotiator.negotiatorlink.util.NegotiatorResourceEvent;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResourceTransitionServiceTest {

  private static final String NEGOTIATION_ID = "neg-1";
  private static final String TICKET_ID = "ticket-42";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String RESOURCE_SOURCE_ID = "biobank:DE:MHH:collection1";
  private static final String RESOURCE_SOURCE_ID_2 = "biobank:DE:MHH:collection2";
  private static final String REQUESTED_EVENT = "MARK_AS_CHECKING_AVAILABILITY";

  @Mock private NegotiatorClient negotiatorClient;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private OrganizationConfig config;
  private ResourceTransitionService service;

  @BeforeEach
  void setUp() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    service = new ResourceTransitionService(negotiatorClient, resourceEventsConfig());
  }

  @Test
  void apply_perResourceValidTransition_emitsSendResourceEventIntent() {
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of(REQUESTED_EVENT, "STEP_AWAY"));

    TransitionResult result =
        service.apply(List.of(mapping("101", RESOURCE_SOURCE_ID)), REQUESTED_EVENT, config);

    assertThat(result.rejection()).isEmpty();
    assertThat(result.intents()).hasSize(1);
    PendingIntent intent = result.intents().getFirst();
    assertThat(intent.outboxIntentType()).isEqualTo(OutboxIntentType.SEND_RESOURCE_EVENT);
    assertThat(intent.targetKind()).isEqualTo(TargetKind.NEGOTIATION);
    assertThat(intent.targetId()).isEqualTo(NEGOTIATION_ID);
    assertThat(intent.organizationExtId()).isEqualTo(ORG_EXT_ID);
    SendResourceEventPayload payload = read(intent, SendResourceEventPayload.class);
    assertThat(payload.negotiationId()).isEqualTo(NEGOTIATION_ID);
    assertThat(payload.resourceSourceId()).isEqualTo(RESOURCE_SOURCE_ID);
    assertThat(payload.event()).isEqualTo(REQUESTED_EVENT);
    verify(negotiatorClient, never()).sendResourceEvent(any(), any(), any(), any());
  }

  @Test
  void apply_perResourceInvalidTransition_returnsRejectionAndNoIntents() {
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of("STEP_AWAY"));

    TransitionResult result =
        service.apply(List.of(mapping("101", RESOURCE_SOURCE_ID)), REQUESTED_EVENT, config);

    assertThat(result.intents()).isEmpty();
    assertThat(result.rejection())
        .hasValueSatisfying(
            r -> {
              assertThat(r.allInvalid()).isTrue();
              assertThat(r.invalidResources())
                  .containsExactly(new InvalidResource(RESOURCE_SOURCE_ID, Set.of("STEP_AWAY")));
            });
    verify(negotiatorClient, never()).sendResourceEvent(any(), any(), any(), any());
  }

  @Test
  void apply_perNegotiationAllValid_emitsBulkIntent() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_NEGOTIATION);
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of(REQUESTED_EVENT));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID_2, config))
        .thenReturn(Set.of(REQUESTED_EVENT));

    TransitionResult result =
        service.apply(
            List.of(mapping("101", RESOURCE_SOURCE_ID), mapping("102", RESOURCE_SOURCE_ID_2)),
            REQUESTED_EVENT,
            config);

    assertThat(result.rejection()).isEmpty();
    assertThat(result.intents()).hasSize(1);
    PendingIntent intent = result.intents().getFirst();
    assertThat(intent.outboxIntentType()).isEqualTo(OutboxIntentType.SEND_BULK_RESOURCE_EVENT);
    assertThat(intent.targetKind()).isEqualTo(TargetKind.NEGOTIATION);
    assertThat(intent.targetId()).isEqualTo(NEGOTIATION_ID);
    SendBulkResourceEventPayload payload = read(intent, SendBulkResourceEventPayload.class);
    assertThat(payload.negotiationId()).isEqualTo(NEGOTIATION_ID);
    assertThat(payload.resourceIds()).containsExactly(101L, 102L);
    assertThat(payload.targetState())
        .isEqualTo(NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.targetState());
    verify(negotiatorClient, never()).sendBulkResourceEvent(any(), any(), any(), any());
  }

  @Test
  void apply_perNegotiationPartialInvalid_returnsRejectionWithoutIntent() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_NEGOTIATION);
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of(REQUESTED_EVENT));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID_2, config))
        .thenReturn(Set.of("STEP_AWAY"));

    TransitionResult result =
        service.apply(
            List.of(mapping("101", RESOURCE_SOURCE_ID), mapping("102", RESOURCE_SOURCE_ID_2)),
            REQUESTED_EVENT,
            config);

    assertThat(result.intents()).isEmpty();
    assertThat(result.rejection())
        .hasValueSatisfying(
            r -> {
              assertThat(r.allInvalid()).isFalse();
              assertThat(r.invalidResources())
                  .containsExactly(new InvalidResource(RESOURCE_SOURCE_ID_2, Set.of("STEP_AWAY")));
            });
    verify(negotiatorClient, never()).sendBulkResourceEvent(any(), any(), any(), any());
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

  private static NegotiatorLinkProperties resourceEventsConfig() {
    NegotiatorLinkProperties props = new NegotiatorLinkProperties();
    props.setResourceEvents(NegotiatorResourceEvent.asMap());
    return props;
  }
}
