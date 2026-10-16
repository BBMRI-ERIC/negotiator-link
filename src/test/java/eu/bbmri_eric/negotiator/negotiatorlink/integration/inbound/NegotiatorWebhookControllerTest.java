package eu.bbmri_eric.negotiator.negotiatorlink.integration.inbound;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil.sha256Hex;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiationStateChangedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiationUpdatedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.postAddedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.resourceStateUpdatedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.signedWebhookPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.bbmri_eric.negotiator.negotiatorlink.adapter.CommentType;
import eu.bbmri_eric.negotiator.negotiatorlink.adapter.HelpdeskClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedPost;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.EventSource;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.OutboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OutboxIntentRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.util.NegotiatorNegotiationEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.util.NegotiatorResourceEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NegotiatorWebhookControllerTest {

  private static final String BODY = "{\"event\":\"test\"}";
  private static final String NEGOTIATION_ID = "neg-1";
  private static final String TICKET_A = "ticket-a";
  private static final String TICKET_B = "ticket-b";
  private static final String TICKET_RESOURCE = "dedup-ticket-1";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final long ORG_ID = 10L;
  private static final String RESOURCE_SOURCE_ID = "bbmri:NL:LUMC:collection:res1";
  private static final String RESOURCE_ORG_EXT_ID = "bbmri:NL:LUMC";

  @Autowired private MockMvc mockMvc;
  @Autowired private TicketMappingRepository ticketMappingRepository;
  @Autowired private OriginatedPostRepository originatedPostRepository;
  @Autowired private InboxEventRepository inboxEventRepository;
  @Autowired private OutboxIntentRepository outboxIntentRepository;
  @MockitoSpyBean private HelpdeskClient helpdeskClient;
  @MockitoBean private NegotiatorClient negotiatorClient;

  @Value("${negotiator-link.webhook.secret}")
  private String secret;

  @BeforeAll
  void seedTicketMappings() {
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_A, "r-1"),
            "src-1",
            String.valueOf(ORG_ID),
            ORG_EXT_ID));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_B, "r-2"),
            "src-2",
            String.valueOf(ORG_ID),
            ORG_EXT_ID));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(NEGOTIATION_ID, TICKET_RESOURCE, "201"),
            RESOURCE_SOURCE_ID,
            "org-1",
            RESOURCE_ORG_EXT_ID));
  }

  @BeforeEach
  void resetMocks() {
    Mockito.reset(helpdeskClient, negotiatorClient);
    Mockito.doNothing().when(helpdeskClient).addComment(any(), any(), any(), any());
    Mockito.doNothing().when(helpdeskClient).addSystemComment(any(), any(), any());
    Mockito.doNothing().when(helpdeskClient).updateNegotiationState(any(), any(), any());
    Mockito.doNothing().when(helpdeskClient).updateResourceState(any(), any(), any());
    Mockito.doNothing().when(helpdeskClient).updateTicketState(any(), any(), any());
    outboxIntentRepository.deleteAll();
    inboxEventRepository.deleteAll();
  }

  @Test
  void receiveWebhook_whenValidSignature_returns200() throws Exception {
    var webhookId = "msg-001";
    var timestamp = Instant.now();
    performWebhook(webhookId, timestamp, BODY).andExpect(status().isOk());
  }

  @Test
  void receiveWebhook_whenSignatureMissing_returns401() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/negotiator")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Webhook-Id", "msg-002")
                .header("Webhook-Timestamp", Instant.now().getEpochSecond())
                // Webhook-Signature intentionally omitted
                .content(utf8(BODY)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void receiveWebhook_whenSignatureTampered_returns401() throws Exception {
    var timestamp = Instant.now();
    mockMvc
        .perform(
            post("/webhooks/negotiator")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Webhook-Id", "msg-003")
                .header("Webhook-Timestamp", timestamp.getEpochSecond())
                .header("Webhook-Signature", "v1,invalidsignature==")
                .content(utf8(BODY)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void receiveWebhook_whenTimestampTooOld_returns401() throws Exception {
    var webhookId = "msg-004";
    var staleTimestamp = Instant.now().minusSeconds(6 * 60);
    performWebhook(webhookId, staleTimestamp, BODY).andExpect(status().isUnauthorized());
  }

  @Test
  void receiveWebhook_whenMalformedJson_returns200AndInboxEventSkipped() throws Exception {
    var webhookId = "msg-malformed-001";
    var timestamp = Instant.now();
    performWebhook(webhookId, timestamp, "not-valid-json{{{").andExpect(status().isOk());

    inboxEventRepository
        .findBySourceAndDeliveryId(EventSource.NEGOTIATOR.name(), webhookId)
        .ifPresent(
            e -> {
              assertNotNull(e.getSkipReason());
              assertThat(e.getSkipReason().startsWith("unparseable"));
            });
  }

  @Test
  void receiveWebhook_whenDuplicate_returns200() throws Exception {
    var webhookId = "msg-005";
    var timestamp = Instant.now();

    // first delivery — marks as seen
    performWebhook(webhookId, timestamp, BODY).andExpect(status().isOk());

    // second delivery with same id — acknowledged silently
    performWebhook(webhookId, timestamp, BODY).andExpect(status().isOk());
  }

  @Test
  void receiveWebhook_postAdded_deduplication_originatingTicketExcluded_otherTicketReceivesComment()
      throws Exception {
    String postId = "post-dedup-p1";
    String postText = "Hello from help desk";
    String textHash = sha256Hex(postText);

    originatedPostRepository.save(new OriginatedPost(textHash, ORG_EXT_ID, TICKET_A));

    when(negotiatorClient.getPost(eq(NEGOTIATION_ID), eq(postId), any()))
        .thenReturn(
            TestUtil.post(
                postId, postText, EntityModelPostDTO.TypeEnum.PRIVATE, String.valueOf(ORG_ID)));

    performWebhook("post-dedup-w1", Instant.now(), postAddedPayload(NEGOTIATION_ID, postId, ORG_ID))
        .andExpect(status().isOk());

    // ticketB must receive the comment; ticketA must be excluded
    verify(helpdeskClient)
        .addComment(eq(TICKET_B), contains(postText), eq(CommentType.PLAIN_TEXT), any());
    verify(helpdeskClient, never()).addComment(eq(TICKET_A), any(), any(), any());
  }

  @Test
  void receiveWebhook_negotiationStateChanged_callsUpdateNegotiationStateOnAllTickets()
      throws Exception {
    String body =
        negotiationStateChangedPayload(
            NEGOTIATION_ID,
            NegotiatorNegotiationEvent.UNPAUSE.toState(),
            NegotiatorNegotiationEvent.PAUSE.toState(),
            NegotiatorNegotiationEvent.PAUSE.name());
    performWebhook("state-change-w1", Instant.now(), body).andExpect(status().isOk());

    verify(helpdeskClient)
        .updateNegotiationState(
            eq(TICKET_A), eq(NegotiatorNegotiationEvent.PAUSE.toState()), any());
    verify(helpdeskClient)
        .updateNegotiationState(
            eq(TICKET_B), eq(NegotiatorNegotiationEvent.PAUSE.toState()), any());
    verify(helpdeskClient, never()).updateTicketState(any(), any(), any());
  }

  @Test
  void receiveWebhook_negotiationStateMappedToTicketState_callsUpdateTicketStateOnAllTickets()
      throws Exception {
    String body =
        negotiationStateChangedPayload(
            NEGOTIATION_ID,
            NegotiatorNegotiationEvent.UNPAUSE.toState(),
            NegotiatorNegotiationEvent.CONCLUDE.toState(),
            NegotiatorNegotiationEvent.CONCLUDE.name());
    performWebhook("state-close-w1", Instant.now(), body).andExpect(status().isOk());

    verify(helpdeskClient)
        .updateNegotiationState(
            eq(TICKET_A), eq(NegotiatorNegotiationEvent.CONCLUDE.toState()), any());
    verify(helpdeskClient)
        .updateNegotiationState(
            eq(TICKET_B), eq(NegotiatorNegotiationEvent.CONCLUDE.toState()), any());
    verify(helpdeskClient).updateTicketState(eq(TICKET_A), eq("closed"), any());
    verify(helpdeskClient).updateTicketState(eq(TICKET_B), eq("closed"), any());
  }

  @Test
  void receiveWebhook_resourceStateMappedToTicketState_updatesResourceStateThenTicketState()
      throws Exception {
    String toState = NegotiatorResourceEvent.GRANT_ACCESS_TO_RESOURCE.targetState();
    String body =
        resourceStateUpdatedPayload(
            NEGOTIATION_ID,
            RESOURCE_SOURCE_ID,
            "ACCESS_CONDITIONS_MET",
            toState,
            NegotiatorResourceEvent.GRANT_ACCESS_TO_RESOURCE.toString());
    performWebhook("resource-close-w1", Instant.now(), body).andExpect(status().isOk());

    verify(helpdeskClient).updateResourceState(eq(TICKET_RESOURCE), eq(toState), any());
    verify(helpdeskClient).updateTicketState(eq(TICKET_RESOURCE), eq("closed"), any());
  }

  @Test
  void receiveWebhook_negotiationUpdated_noMappedTickets_doesNotAddComment() throws Exception {
    performWebhook("upd-w1", Instant.now(), negotiationUpdatedPayload("upd-neg-0"))
        .andExpect(status().isOk());

    verify(helpdeskClient, never()).addComment(anyString(), anyString(), any(), any());
  }

  @Test
  void receiveWebhook_negotiationUpdated_postsUpdateCommentToAllMappedTickets() throws Exception {
    String negotiationId = "upd-neg-1";
    String ticketA = "upd-ticket-a";
    String ticketB = "upd-ticket-b";
    String orgExtId = "bbmri:DE:MHH";
    long orgId = 10L;

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketA, "1"),
            "src-1",
            String.valueOf(orgId),
            orgExtId));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketB, "2"),
            "src-2",
            String.valueOf(orgId),
            orgExtId));

    when(negotiatorClient.getNegotiation(eq(negotiationId), any()))
        .thenReturn(TestUtil.negotiation(negotiationId, "NEG-1", "IN_PROGRESS"));
    when(negotiatorClient.getResources(eq(negotiationId), any()))
        .thenReturn(
            List.of(
                TestUtil.resource(1L, "src-1", "Collection A", orgExtId, orgId),
                TestUtil.resource(2L, "src-2", "Collection B", orgExtId, orgId)));

    performWebhook("upd-w2", Instant.now(), negotiationUpdatedPayload(negotiationId))
        .andExpect(status().isOk());

    verify(helpdeskClient, times(1))
        .addComment(eq(ticketA), contains("updated"), eq(CommentType.HTML), any());
    verify(helpdeskClient, times(1))
        .addComment(eq(ticketB), contains("updated"), eq(CommentType.HTML), any());
  }

  @Test
  void receiveWebhook_negotiationUpdated_perNegotiationStrategy_postsOneCommentWithAllResources()
      throws Exception {
    String negotiationId = "upd-neg-2";
    String ticketId = "upd-ticket-perneg";
    String orgExtId = "bbmri:NL:LUMC";
    long orgId = 20L;

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketId, "1"),
            "src-1",
            String.valueOf(orgId),
            orgExtId));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketId, "2"),
            "src-2",
            String.valueOf(orgId),
            orgExtId));

    when(negotiatorClient.getNegotiation(eq(negotiationId), any()))
        .thenReturn(TestUtil.negotiation(negotiationId, "NEG-2", "IN_PROGRESS"));
    when(negotiatorClient.getResources(eq(negotiationId), any()))
        .thenReturn(
            List.of(
                TestUtil.resource(1L, "src-1", "Collection A", orgExtId, orgId),
                TestUtil.resource(2L, "src-2", "Collection B", orgExtId, orgId)));

    performWebhook("upd-w3", Instant.now(), negotiationUpdatedPayload(negotiationId))
        .andExpect(status().isOk());

    verify(helpdeskClient, times(1))
        .addComment(eq(ticketId), contains("updated"), eq(CommentType.HTML), any());
  }

  @Test
  void receiveWebhook_negotiationUpdated_oneTicketFails_otherTicketStillReceivesComment()
      throws Exception {
    String negotiationId = "upd-neg-3";
    String ticketA = "upd-ticket-fail";
    String ticketB = "upd-ticket-ok";
    String orgExtId = "bbmri:DE:MHH";
    long orgId = 10L;

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketA, "1"),
            "src-1",
            String.valueOf(orgId),
            orgExtId));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketB, "2"),
            "src-2",
            String.valueOf(orgId),
            orgExtId));

    when(negotiatorClient.getNegotiation(eq(negotiationId), any()))
        .thenReturn(TestUtil.negotiation(negotiationId, "NEG-3", "IN_PROGRESS"));
    when(negotiatorClient.getResources(eq(negotiationId), any()))
        .thenReturn(
            List.of(
                TestUtil.resource(1L, "src-1", "Collection A", orgExtId, orgId),
                TestUtil.resource(2L, "src-2", "Collection B", orgExtId, orgId)));
    doThrow(new RuntimeException("helpdesk down"))
        .when(helpdeskClient)
        .addComment(eq(ticketA), anyString(), any(), any());

    performWebhook("upd-w4", Instant.now(), negotiationUpdatedPayload(negotiationId))
        .andExpect(status().isOk());

    verify(helpdeskClient, times(1))
        .addComment(eq(ticketB), contains("updated"), eq(CommentType.HTML), any());
  }

  private ResultActions performWebhook(String webhookId, Instant timestamp, String body)
      throws Exception {
    ResultActions result =
        mockMvc.perform(
            post("/webhooks/negotiator")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Webhook-Id", webhookId)
                .header("Webhook-Timestamp", timestamp.getEpochSecond())
                .header(
                    "Webhook-Signature", signedWebhookPayload(secret, webhookId, timestamp, body))
                .content(utf8(body)));
    if (result.andReturn().getResponse().getStatus() == 200) {
      awaitProcessed(EventSource.NEGOTIATOR.name(), webhookId);
    }
    return result;
  }

  private void awaitProcessed(String source, String deliveryId) {
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                inboxEventRepository
                    .findBySourceAndDeliveryId(source, deliveryId)
                    .map(e -> e.getStatus() != InboxStatus.RECEIVED)
                    .orElse(false));
    // Side effects now happen in the outbox deliverer; wait until all intents are delivered so the
    // downstream verify(helpdeskClient...) assertions observe the applied effects.
    await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                outboxIntentRepository.countByStatusIn(
                        List.of(OutboxStatus.PENDING, OutboxStatus.IN_PROGRESS))
                    == 0);
  }
}
