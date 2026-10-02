package eu.bbmri_eric.negotiator.negotiatorlink.integration.inbound;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil.sha256Hex;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiationStateChangedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiationUpdatedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.postAddedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.resourceStateUpdatedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.signedWebhookPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.utf8;
import static org.assertj.core.api.Assertions.assertThat;
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
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.OriginatedTransition;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedPostRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.OriginatedTransitionRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.util.NegotiatorNegotiationEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.util.NegotiatorResourceEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
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
  private static final String AVAILABLE_SYSTEM_COMMENT =
      "Resource '%s' changed the status to 'Resource Made Available'".formatted(RESOURCE_SOURCE_ID);
  private static final String CONCLUDED_SYSTEM_COMMENT =
      "Negotiation status changed to 'Concluded'";

  @Autowired private MockMvc mockMvc;
  @Autowired private TicketMappingRepository ticketMappingRepository;
  @Autowired private OriginatedTransitionRepository originatedTransitionRepository;
  @Autowired private OriginatedPostRepository originatedPostRepository;
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
  void receiveWebhook_whenDuplicate_returns200() throws Exception {
    var webhookId = "msg-005";
    var timestamp = Instant.now();

    // first delivery — marks as seen
    performWebhook(webhookId, timestamp, BODY).andExpect(status().isOk());

    // second delivery with same id — acknowledged silently
    performWebhook(webhookId, timestamp, BODY).andExpect(status().isOk());
  }

  @Test
  void
      receiveWebhook_resourceStateUpdated_deduplication_firstCallsAdapter_secondDiscarded_thirdDeletesRow()
          throws Exception {
    String toState = NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.targetState();

    originatedTransitionRepository.save(
        new OriginatedTransition(NEGOTIATION_ID, TICKET_RESOURCE, 3, toState));

    // First webhook: adapter must be called once to update the state
    sendResourceStateWebhook("dedup-w1", NEGOTIATION_ID, RESOURCE_SOURCE_ID, toState);
    verify(helpdeskClient, times(1)).updateResourceState(eq(TICKET_RESOURCE), eq(toState), any());
    assertThat(
            originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
                NEGOTIATION_ID, TICKET_RESOURCE, toState))
        .isPresent()
        .hasValueSatisfying(t -> assertThat(t.getReceivedCount()).isEqualTo(1));

    // Second webhook: adapter must NOT be called again, row still exists
    sendResourceStateWebhook("dedup-w2", NEGOTIATION_ID, RESOURCE_SOURCE_ID, toState);
    verify(helpdeskClient, times(1)).updateResourceState(any(), any(), any());
    assertThat(
            originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
                NEGOTIATION_ID, TICKET_RESOURCE, toState))
        .isPresent()
        .hasValueSatisfying(t -> assertThat(t.getReceivedCount()).isEqualTo(2));

    // Third webhook: adapter must NOT be called again, row is deleted
    sendResourceStateWebhook("dedup-w3", NEGOTIATION_ID, RESOURCE_SOURCE_ID, toState);
    verify(helpdeskClient, times(1)).updateResourceState(any(), any(), any());
    assertThat(
            originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
                NEGOTIATION_ID, TICKET_RESOURCE, toState))
        .isEmpty();
  }

  @Test
  void receiveWebhook_postAdded_deduplication_originatingTicketExcluded_otherTicketReceivesComment()
      throws Exception {
    String postId = "post-dedup-p1";
    String postText = "Hello from help desk";
    String textHash = sha256Hex(postText);

    originatedPostRepository.save(new OriginatedPost(textHash, String.valueOf(ORG_ID), TICKET_A));

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
    Mockito.doNothing()
        .when(helpdeskClient)
        .updateNegotiationState(
            eq(TICKET_A), eq(NegotiatorNegotiationEvent.CONCLUDE.toState()), any());
    Mockito.doNothing()
        .when(helpdeskClient)
        .updateNegotiationState(
            eq(TICKET_B), eq(NegotiatorNegotiationEvent.CONCLUDE.toState()), any());
    Mockito.doNothing()
        .when(helpdeskClient)
        .addSystemComment(eq(TICKET_A), eq(CONCLUDED_SYSTEM_COMMENT), any());
    Mockito.doNothing()
        .when(helpdeskClient)
        .addSystemComment(eq(TICKET_B), eq(CONCLUDED_SYSTEM_COMMENT), any());
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
  void receiveWebhook_negotiationStateUpdateFails_doesNotUpdateTicketState() throws Exception {
    Mockito.doThrow(new RuntimeException("help desk unavailable"))
        .when(helpdeskClient)
        .updateNegotiationState(any(), eq(NegotiatorNegotiationEvent.CONCLUDE.toState()), any());
    String body =
        negotiationStateChangedPayload(
            NEGOTIATION_ID,
            NegotiatorNegotiationEvent.UNPAUSE.toState(),
            NegotiatorNegotiationEvent.CONCLUDE.toState(),
            NegotiatorNegotiationEvent.CONCLUDE.name());
    performWebhook("state-close-fail-w1", Instant.now(), body).andExpect(status().isOk());

    verify(helpdeskClient)
        .updateNegotiationState(
            eq(TICKET_A), eq(NegotiatorNegotiationEvent.CONCLUDE.toState()), any());
    verify(helpdeskClient)
        .updateNegotiationState(
            eq(TICKET_B), eq(NegotiatorNegotiationEvent.CONCLUDE.toState()), any());
    verify(helpdeskClient, never()).updateTicketState(any(), any(), any());
  }

  @Test
  void receiveWebhook_resourceStateMappedToTicketState_updatesResourceStateThenTicketState()
      throws Exception {
    String toState = NegotiatorResourceEvent.GRANT_ACCESS_TO_RESOURCE.targetState();
    Mockito.doNothing()
        .when(helpdeskClient)
        .updateResourceState(eq(TICKET_RESOURCE), eq(toState), any());
    Mockito.doNothing()
        .when(helpdeskClient)
        .addSystemComment(eq(TICKET_RESOURCE), eq(AVAILABLE_SYSTEM_COMMENT), any());
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
  void receiveWebhook_resourceStateUpdateFails_doesNotUpdateTicketState() throws Exception {
    String toState = NegotiatorResourceEvent.GRANT_ACCESS_TO_RESOURCE.targetState();
    Mockito.doThrow(new RuntimeException("help desk unavailable"))
        .when(helpdeskClient)
        .updateResourceState(eq(TICKET_RESOURCE), eq(toState), any());
    String body =
        resourceStateUpdatedPayload(
            NEGOTIATION_ID,
            RESOURCE_SOURCE_ID,
            "ACCESS_CONDITIONS_MET",
            toState,
            NegotiatorResourceEvent.GRANT_ACCESS_TO_RESOURCE.toString());
    performWebhook("resource-close-fail-w1", Instant.now(), body).andExpect(status().isOk());

    verify(helpdeskClient).updateResourceState(eq(TICKET_RESOURCE), eq(toState), any());
    verify(helpdeskClient, never()).updateTicketState(any(), any(), any());
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

  private void sendResourceStateWebhook(
      String webhookId, String negotiationId, String resourceId, String toState) throws Exception {
    var timestamp = Instant.now();
    String body =
        resourceStateUpdatedPayload(
            negotiationId,
            resourceId,
            "SUBMITTED",
            toState,
            NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.toString());
    performWebhook(webhookId, timestamp, body).andExpect(status().isOk());
  }

  private ResultActions performWebhook(String webhookId, Instant timestamp, String body)
      throws Exception {
    return mockMvc.perform(
        post("/webhooks/negotiator")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header("Webhook-Id", webhookId)
            .header("Webhook-Timestamp", timestamp.getEpochSecond())
            .header("Webhook-Signature", signedWebhookPayload(secret, webhookId, timestamp, body))
            .content(utf8(body)));
  }
}
