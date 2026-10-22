package eu.bbmri_eric.negotiator.negotiatorlink.component;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.hmacSha1Hex;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.PostCreateDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.integration.IntegrationBase;
import eu.bbmri_eric.negotiator.negotiatorlink.util.NegotiatorResourceEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

class ZammadToNegotiatorFlowIT extends IntegrationBase {

  private static final String TICKET_ID = "1";
  private static final String NEGOTIATION_ID = "neg-cb-1";
  private static final String RESOURCE_SOURCE_ID = "src-cb-1";
  private static final String RESOURCE_ID = "100";
  private static final String ORGANIZATION_ID = "42";

  private static final String ARTICLE_BODY = "Internal Message";
  private static final String AUTHOR_FIRSTNAME = "John";
  private static final String AUTHOR_LASTNAME = "Smith";
  private static final String AUTHOR_EMAIL = "john.smith@example.com";
  private static final String SIGNED_POST_TEXT =
      "From: "
          + AUTHOR_FIRSTNAME
          + " "
          + AUTHOR_LASTNAME
          + " <"
          + AUTHOR_EMAIL
          + ">\n\n"
          + ARTICLE_BODY;

  private static final String ARTICLE_BODY_WITH_TRANSITION =
      "Public Message With change resource state";
  private static final String SIGNED_POST_TEXT_WITH_TRANSITION =
      "From: "
          + AUTHOR_FIRSTNAME
          + " "
          + AUTHOR_LASTNAME
          + " <"
          + AUTHOR_EMAIL
          + ">\n\n"
          + ARTICLE_BODY_WITH_TRANSITION;

  private static final String PAYLOAD_NEW_ARTICLE = "webhookPayloadWhenNewPublicArticle.json";
  private static final String PAYLOAD_RESOURCE_TRANSITION =
      "webhookPayloadWhenResourceTransitionOnly.json";
  private static final String PAYLOAD_ARTICLE_AND_TRANSITION =
      "webhookPayloadWhenBothNewPublicArticleAndResourceStateTransition.json";
  private static final String PAYLOAD_INTERNAL_ARTICLE_AND_TRANSITION =
      "webhookPayloadWhenBothNewInternalArticleAndResourceStateTransition.json";
  private static final String PAYLOAD_NEW_INTERNAL_ARTICLE =
      "webhookPayloadWhenNewInternalArticle.json";

  private static final String PER_RESOURCE_ORG = "bbmri:DE:MHH";
  private static final String PER_RESOURCE_SECRET = "test-callback-secret";

  private static final String PER_NEGOTIATION_ORG = "bbmri:NL:LUMC";
  private static final String PER_NEGOTIATION_SECRET = "test-callback-secret-lumc";

  @Autowired private MockMvc mockMvc;
  @Autowired private TicketMappingRepository ticketMappingRepository;
  @Autowired private InboxEventRepository inboxEventRepository;
  @Autowired private OutboxIntentRepository outboxIntentRepository;
  @Autowired private RestClient.Builder restClientBuilder;

  @MockitoBean private NegotiatorClient negotiatorClient;

  private MockRestServiceServer zammadServer;

  @BeforeEach
  void setUp() {
    zammadServer = MockRestServiceServer.bindTo(restClientBuilder).build();
    when(negotiatorClient.getNegotiation(eq(NEGOTIATION_ID), any(OrganizationConfig.class)))
        .thenReturn(new EntityModelNegotiationDTO().privatePostsEnabled(true));
  }

  @Test
  void postFromHelpdesk_perResourceOrg_forwardsPostToNegotiator() throws Exception {
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, RESOURCE_ID),
            RESOURCE_SOURCE_ID,
            ORGANIZATION_ID,
            PER_RESOURCE_ORG));

    postCallback(PER_RESOURCE_ORG, PER_RESOURCE_SECRET, loadFile(PAYLOAD_NEW_ARTICLE));

    verify(negotiatorClient)
        .createPost(
            eq(NEGOTIATION_ID),
            argThat(
                p ->
                    SIGNED_POST_TEXT.equals(p.getText())
                        && PostCreateDTO.TypeEnum.PRIVATE.equals(p.getType())),
            any(OrganizationConfig.class));
    zammadServer.verify();
  }

  @Test
  void postFromHelpdesk_internalMsg_doNotForwardsPostToNegotiator() throws Exception {
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, RESOURCE_ID),
            RESOURCE_SOURCE_ID,
            ORGANIZATION_ID,
            PER_RESOURCE_ORG));

    postCallback(PER_RESOURCE_ORG, PER_RESOURCE_SECRET, loadFile(PAYLOAD_NEW_INTERNAL_ARTICLE));

    verify(negotiatorClient, never()).createPost(any(), any(), any());
  }

  @Test
  void postFromHelpdesk_perNegotiationOrg_forwardsPostToNegotiator() throws Exception {
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "200"),
            "src-cb-2a",
            ORGANIZATION_ID,
            PER_NEGOTIATION_ORG));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, "201"),
            "src-cb-2b",
            ORGANIZATION_ID,
            PER_NEGOTIATION_ORG));

    postCallback(PER_NEGOTIATION_ORG, PER_NEGOTIATION_SECRET, loadFile(PAYLOAD_NEW_ARTICLE));

    verify(negotiatorClient)
        .createPost(
            eq(NEGOTIATION_ID),
            argThat(
                p ->
                    SIGNED_POST_TEXT.equals(p.getText())
                        && PostCreateDTO.TypeEnum.PRIVATE.equals(p.getType())),
            any(OrganizationConfig.class));
    zammadServer.verify();
  }

  @Test
  void resourceTransition_perResourceOrg_sendsResourceEventToNegotiator() throws Exception {
    String event = "MARK_AS_CHECKING_AVAILABILITY";

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, RESOURCE_ID),
            RESOURCE_SOURCE_ID,
            ORGANIZATION_ID,
            PER_RESOURCE_ORG));

    when(negotiatorClient.getPossibleResourceEvents(
            eq(NEGOTIATION_ID), eq(RESOURCE_SOURCE_ID), any(OrganizationConfig.class)))
        .thenReturn(Set.of(event));

    postCallback(PER_RESOURCE_ORG, PER_RESOURCE_SECRET, loadFile(PAYLOAD_RESOURCE_TRANSITION));

    verify(negotiatorClient)
        .sendResourceEvent(
            eq(NEGOTIATION_ID), eq(RESOURCE_SOURCE_ID), eq(event), any(OrganizationConfig.class));
    zammadServer.verify();
  }

  @Test
  void resourceTransition_perNegotiationOrg_sendsBulkResourceEventToNegotiator() throws Exception {
    String resourceSourceId1 = "src-cb-4a";
    String resourceSourceId2 = "src-cb-4b";
    Long resourceId1 = 400L;
    Long resourceId2 = 401L;
    String event = "MARK_AS_CHECKING_AVAILABILITY";

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, String.valueOf(resourceId1)),
            resourceSourceId1,
            ORGANIZATION_ID,
            PER_NEGOTIATION_ORG));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, String.valueOf(resourceId2)),
            resourceSourceId2,
            ORGANIZATION_ID,
            PER_NEGOTIATION_ORG));

    when(negotiatorClient.getPossibleResourceEvents(
            eq(NEGOTIATION_ID), eq(resourceSourceId1), any(OrganizationConfig.class)))
        .thenReturn(Set.of(event));
    when(negotiatorClient.getPossibleResourceEvents(
            eq(NEGOTIATION_ID), eq(resourceSourceId2), any(OrganizationConfig.class)))
        .thenReturn(Set.of(event));

    postCallback(
        PER_NEGOTIATION_ORG, PER_NEGOTIATION_SECRET, loadFile(PAYLOAD_RESOURCE_TRANSITION));

    verify(negotiatorClient)
        .sendBulkResourceEvent(
            eq(NEGOTIATION_ID),
            argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(resourceId1, resourceId2))),
            eq(NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.targetState()),
            any(OrganizationConfig.class));
    zammadServer.verify();
  }

  @Test
  void articleWithResourceTransition_perResourceOrg_sendsPostAndResourceEvent() throws Exception {
    String event = NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.toString();

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, RESOURCE_ID),
            RESOURCE_SOURCE_ID,
            ORGANIZATION_ID,
            PER_RESOURCE_ORG));

    when(negotiatorClient.getPossibleResourceEvents(
            eq(NEGOTIATION_ID), eq(RESOURCE_SOURCE_ID), any(OrganizationConfig.class)))
        .thenReturn(Set.of(event));

    postCallback(PER_RESOURCE_ORG, PER_RESOURCE_SECRET, loadFile(PAYLOAD_ARTICLE_AND_TRANSITION));

    verify(negotiatorClient)
        .createPost(
            eq(NEGOTIATION_ID),
            argThat(
                p ->
                    SIGNED_POST_TEXT_WITH_TRANSITION.equals(p.getText())
                        && PostCreateDTO.TypeEnum.PRIVATE.equals(p.getType())),
            any(OrganizationConfig.class));
    verify(negotiatorClient)
        .sendResourceEvent(
            eq(NEGOTIATION_ID), eq(RESOURCE_SOURCE_ID), eq(event), any(OrganizationConfig.class));
    zammadServer.verify();
  }

  @Test
  void internalArticleWithResourceTransition_perResourceOrg_discardsPostButSendsResourceEvent()
      throws Exception {
    String event = "MARK_AS_CHECKING_AVAILABILITY";

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_ID, RESOURCE_ID),
            RESOURCE_SOURCE_ID,
            ORGANIZATION_ID,
            PER_RESOURCE_ORG));

    when(negotiatorClient.getPossibleResourceEvents(
            eq(NEGOTIATION_ID), eq(RESOURCE_SOURCE_ID), any(OrganizationConfig.class)))
        .thenReturn(Set.of(event));

    postCallback(
        PER_RESOURCE_ORG, PER_RESOURCE_SECRET, loadFile(PAYLOAD_INTERNAL_ARTICLE_AND_TRANSITION));

    verify(negotiatorClient, never()).createPost(any(), any(), any());
    verify(negotiatorClient)
        .sendResourceEvent(
            eq(NEGOTIATION_ID), eq(RESOURCE_SOURCE_ID), eq(event), any(OrganizationConfig.class));
    zammadServer.verify();
  }

  private void postCallback(String orgExternalId, String secret, String body) throws Exception {
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    String sig = "sha1=" + hmacSha1Hex(secret, bodyBytes);
    String deliveryId = orgExternalId + "-" + System.nanoTime();

    mockMvc
        .perform(
            post("/webhooks/helpdesk/{org}", orgExternalId)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Hub-Signature", sig)
                .header("X-Zammad-Delivery", deliveryId)
                .content(bodyBytes))
        .andExpect(status().isOk());
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                inboxEventRepository
                    .findBySourceAndDeliveryId(orgExternalId, deliveryId)
                    .map(e -> e.getStatus() != InboxStatus.RECEIVED)
                    .orElse(false));
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                outboxIntentRepository.countByStatusIn(
                        List.of(OutboxStatus.PENDING, OutboxStatus.IN_PROGRESS))
                    == 0);
  }

  private static String loadFile(String filename) {
    try {
      return new ClassPathResource("fixtures/zammad/" + filename)
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
