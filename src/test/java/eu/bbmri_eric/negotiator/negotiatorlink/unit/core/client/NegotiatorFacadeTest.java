package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorFacade;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfigRegistry;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.TicketStrategy;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OutboxIntent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxIntentType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.TargetKind;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.payload.SendResourceEventPayload;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.PayloadUtil;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.Link;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NegotiatorFacadeTest {

  private static final long ROW_ID = 7L;
  private static final String NEGOTIATION_ID = "neg-1";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final String RESOURCE_SOURCE_ID = "src-1";

  @Mock private NegotiatorClient negotiatorClient;
  @Mock private OrganizationConfigRegistry organizationConfigRegistry;
  @Mock private OutboxIntentRepository outboxIntentRepository;

  private NegotiatorFacade facade;

  @BeforeEach
  void setUp() {
    facade =
        new NegotiatorFacade(negotiatorClient, organizationConfigRegistry, outboxIntentRepository);
  }

  @Test
  void findNegotiationForRequiredFormComment_sendResourceEventWithConfig_returnsNegotiation() {
    when(outboxIntentRepository.findById(ROW_ID))
        .thenReturn(Optional.of(sendResourceEventIntent()));
    OrganizationConfig config =
        TestUtil.organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    when(organizationConfigRegistry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    EntityModelNegotiationDTO negotiation = negotiationWithUiLink("http://nego/abc");
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config)).thenReturn(negotiation);

    assertThat(facade.findNegotiationForRequiredFormComment(ROW_ID)).containsSame(negotiation);
  }

  @Test
  void findNegotiationForRequiredFormComment_notSendResourceEvent_returnsEmpty() {
    OutboxIntent addComment =
        new OutboxIntent(
            1L,
            OutboxIntentType.ADD_COMMENT,
            TargetKind.TICKET.name(),
            "ticket-7",
            NEGOTIATION_ID,
            ORG_EXT_ID,
            "{}");
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.of(addComment));

    assertThat(facade.findNegotiationForRequiredFormComment(ROW_ID)).isEmpty();
    verify(negotiatorClient, never()).getNegotiation(any(), any());
  }

  @Test
  void findNegotiationForRequiredFormComment_intentGone_returnsEmpty() {
    when(outboxIntentRepository.findById(ROW_ID)).thenReturn(Optional.empty());

    assertThat(facade.findNegotiationForRequiredFormComment(ROW_ID)).isEmpty();
    verify(negotiatorClient, never()).getNegotiation(any(), any());
  }

  @Test
  void findNegotiationForRequiredFormComment_configMissing_returnsEmpty() {
    when(outboxIntentRepository.findById(ROW_ID))
        .thenReturn(Optional.of(sendResourceEventIntent()));
    when(organizationConfigRegistry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.empty());

    assertThat(facade.findNegotiationForRequiredFormComment(ROW_ID)).isEmpty();
    verify(negotiatorClient, never()).getNegotiation(any(), any());
  }

  @Test
  void findNegotiationForRequiredFormComment_fetchThrows_returnsEmpty() {
    when(outboxIntentRepository.findById(ROW_ID))
        .thenReturn(Optional.of(sendResourceEventIntent()));
    OrganizationConfig config =
        TestUtil.organizationConfig(ORG_EXT_ID, TicketStrategy.PER_RESOURCE);
    when(organizationConfigRegistry.findByExternalId(ORG_EXT_ID)).thenReturn(Optional.of(config));
    when(negotiatorClient.getNegotiation(NEGOTIATION_ID, config))
        .thenThrow(new RuntimeException("connection reset"));

    assertThat(facade.findNegotiationForRequiredFormComment(ROW_ID)).isEmpty();
  }

  private OutboxIntent sendResourceEventIntent() {
    OutboxIntent intent =
        new OutboxIntent(
            1L,
            OutboxIntentType.SEND_RESOURCE_EVENT,
            TargetKind.NEGOTIATION.name(),
            NEGOTIATION_ID,
            NEGOTIATION_ID,
            ORG_EXT_ID,
            payloadJson());
    intent.setRowId(ROW_ID);
    return intent;
  }

  private String payloadJson() {
    try {
      return PayloadUtil.serialize(
          new SendResourceEventPayload(
              NEGOTIATION_ID, RESOURCE_SOURCE_ID, "MARK_AS_CHECKING_AVAILABILITY"));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private EntityModelNegotiationDTO negotiationWithUiLink(String href) {
    Link link = new Link();
    link.setHref(href);
    EntityModelNegotiationDTO negotiation = new EntityModelNegotiationDTO();
    negotiation.setLinks(Map.of("ui", link));
    return negotiation;
  }
}
