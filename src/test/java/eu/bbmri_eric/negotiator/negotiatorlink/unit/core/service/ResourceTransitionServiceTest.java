package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.service;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.organizationConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.NegotiatorLinkProperties;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedTransition;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService.InvalidResource;
import eu.bbmri_eric.negotiator.negotiatorlink.core.service.ResourceTransitionService.Rejection;
import eu.bbmri_eric.negotiator.negotiatorlink.util.NegotiatorResourceEvent;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
  @Mock private OriginatedTransitionRepository originatedTransitionRepository;

  private OrganizationConfig config;
  private ResourceTransitionService service;

  @BeforeEach
  void setUp() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    service =
        new ResourceTransitionService(
            negotiatorClient, originatedTransitionRepository, resourceEventsConfig());
  }

  @Test
  void apply_perResourceValidTransition_sendsResourceEventAndReturnsEmpty() {
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of(REQUESTED_EVENT, "STEP_AWAY"));

    Optional<Rejection> rejection =
        service.apply(List.of(mapping("101", RESOURCE_SOURCE_ID)), REQUESTED_EVENT, config);

    assertThat(rejection).isEmpty();
    verify(negotiatorClient)
        .sendResourceEvent(
            eq(NEGOTIATION_ID), eq(RESOURCE_SOURCE_ID), eq(REQUESTED_EVENT), eq(config));
  }

  @Test
  void apply_perResourceInvalidTransition_returnsRejectionAndMakesNoTransitionCalls() {
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of("STEP_AWAY"));

    Optional<Rejection> rejection =
        service.apply(List.of(mapping("101", RESOURCE_SOURCE_ID)), REQUESTED_EVENT, config);

    assertThat(rejection)
        .hasValueSatisfying(
            r -> {
              assertThat(r.allInvalid()).isTrue();
              assertThat(r.invalidResources())
                  .containsExactly(new InvalidResource(RESOURCE_SOURCE_ID, Set.of("STEP_AWAY")));
            });
    verify(negotiatorClient, never()).sendResourceEvent(any(), any(), any(), any());
    verify(originatedTransitionRepository, never()).save(any());
  }

  @Test
  void apply_perNegotiationAllValid_savesOriginatedTransitionAndSendsBulk() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_NEGOTIATION);
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of(REQUESTED_EVENT));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID_2, config))
        .thenReturn(Set.of(REQUESTED_EVENT));

    Optional<Rejection> rejection =
        service.apply(
            List.of(mapping("101", RESOURCE_SOURCE_ID), mapping("102", RESOURCE_SOURCE_ID_2)),
            REQUESTED_EVENT,
            config);

    assertThat(rejection).isEmpty();

    var captor = ArgumentCaptor.forClass(OriginatedTransition.class);
    verify(originatedTransitionRepository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getNegotiationId()).isEqualTo(NEGOTIATION_ID);
    assertThat(saved.getTicketId()).isEqualTo(TICKET_ID);
    assertThat(saved.getExpectedCount()).isEqualTo(2);
    assertThat(saved.getNewState())
        .isEqualTo(NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.targetState());

    verify(negotiatorClient)
        .sendBulkResourceEvent(
            eq(NEGOTIATION_ID),
            eq(List.of(101L, 102L)),
            eq(NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.targetState()),
            eq(config));
  }

  @Test
  void apply_perNegotiationPartialInvalid_returnsRejectionWithoutSaveOrBulk() {
    config = organizationConfig(ORG_EXT_ID, TicketStrategy.PER_NEGOTIATION);
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID, config))
        .thenReturn(Set.of(REQUESTED_EVENT));
    when(negotiatorClient.getPossibleResourceEvents(NEGOTIATION_ID, RESOURCE_SOURCE_ID_2, config))
        .thenReturn(Set.of("STEP_AWAY"));

    Optional<Rejection> rejection =
        service.apply(
            List.of(mapping("101", RESOURCE_SOURCE_ID), mapping("102", RESOURCE_SOURCE_ID_2)),
            REQUESTED_EVENT,
            config);

    assertThat(rejection)
        .hasValueSatisfying(
            r -> {
              assertThat(r.allInvalid()).isFalse();
              assertThat(r.invalidResources())
                  .containsExactly(new InvalidResource(RESOURCE_SOURCE_ID_2, Set.of("STEP_AWAY")));
            });
    verify(originatedTransitionRepository, never()).save(any());
    verify(negotiatorClient, never()).sendBulkResourceEvent(any(), any(), any(), any());
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
