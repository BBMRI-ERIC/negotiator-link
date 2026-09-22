package eu.bbmri_eric.negotiator.negotiatorlink.integration.inbound;

import static eu.bbmri_eric.negotiator.negotiatorlink.core.util.HashUtil.sha256Hex;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiationStateChangedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.postAddedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.resourceStateUpdatedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.signedWebhookPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.api.Test;
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
class NegotiatorWebhookControllerTest {

  private static final String BODY = "{\"event\":\"test\"}";

  @Autowired private MockMvc mockMvc;
  @Autowired private TicketMappingRepository ticketMappingRepository;
  @Autowired private OriginatedTransitionRepository originatedTransitionRepository;
  @Autowired private OriginatedPostRepository originatedPostRepository;
  @MockitoSpyBean private HelpdeskClient helpdeskClient;
  @MockitoBean private NegotiatorClient negotiatorClient;

  @Value("${negotiator-link.webhook.secret}")
  private String secret;

  @org.junit.jupiter.api.BeforeEach
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
    String negotiationId = "dedup-neg-1";
    String ticketId = "dedup-ticket-1";
    String orgExtId = "bbmri:NL:LUMC";
    String resourceSourceId = "bbmri:NL:LUMC:collection:res1";
    String toState = NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.targetState();

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketId, "201"),
            resourceSourceId,
            "org-1",
            orgExtId));

    originatedTransitionRepository.save(
        new OriginatedTransition(negotiationId, ticketId, 3, toState));

    // First webhook: adapter must be called once to update the state
    sendResourceStateWebhook("dedup-w1", negotiationId, resourceSourceId, toState);
    verify(helpdeskClient, times(1)).updateResourceState(eq(ticketId), eq(toState), any());
    assertThat(
            originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
                negotiationId, ticketId, toState))
        .isPresent()
        .hasValueSatisfying(t -> assertThat(t.getReceivedCount()).isEqualTo(1));

    // Second webhook: adapter must NOT be called again, row still exists
    sendResourceStateWebhook("dedup-w2", negotiationId, resourceSourceId, toState);
    verify(helpdeskClient, times(1)).updateResourceState(any(), any(), any());
    assertThat(
            originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
                negotiationId, ticketId, toState))
        .isPresent()
        .hasValueSatisfying(t -> assertThat(t.getReceivedCount()).isEqualTo(2));

    // Third webhook: adapter must NOT be called again, row is deleted
    sendResourceStateWebhook("dedup-w3", negotiationId, resourceSourceId, toState);
    verify(helpdeskClient, times(1)).updateResourceState(any(), any(), any());
    assertThat(
            originatedTransitionRepository.findByNegotiationIdAndTicketIdAndNewState(
                negotiationId, ticketId, toState))
        .isEmpty();
  }

  @Test
  void receiveWebhook_postAdded_deduplication_originatingTicketExcluded_otherTicketReceivesComment()
      throws Exception {
    String negotiationId = "post-dedup-neg-1";
    String postId = "post-dedup-p1";
    String ticketA = "post-dedup-ticket-a";
    String ticketB = "post-dedup-ticket-b";
    String orgExtId = "bbmri:DE:MHH";
    long orgId = 10L;
    String postText = "Hello from help desk";
    String textHash = sha256Hex(postText);

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketA, "r-1"),
            "src-1",
            String.valueOf(orgId),
            orgExtId));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketB, "r-2"),
            "src-2",
            String.valueOf(orgId),
            orgExtId));

    originatedPostRepository.save(new OriginatedPost(textHash, String.valueOf(orgId), ticketA));

    when(negotiatorClient.getPost(eq(negotiationId), eq(postId), any()))
        .thenReturn(
            TestUtil.post(
                postId, postText, EntityModelPostDTO.TypeEnum.PRIVATE, String.valueOf(orgId)));

    performWebhook("post-dedup-w1", Instant.now(), postAddedPayload(negotiationId, postId, orgId))
        .andExpect(status().isOk());

    // ticketB must receive the comment; ticketA must be excluded
    verify(helpdeskClient).addComment(eq(ticketB), contains(postText), any());
    verify(helpdeskClient, never()).addComment(eq(ticketA), any(), any());
  }

  @Test
  void receiveWebhook_negotiationStateChanged_callsUpdateNegotiationStateOnAllTickets()
      throws Exception {
    String negotiationId = "state-change-neg-1";
    String ticketA = "state-ticket-a";
    String ticketB = "state-ticket-b";
    String orgExtId = "bbmri:DE:MHH";
    long orgId = 10L;

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketA, "r-1"),
            "src-1",
            String.valueOf(orgId),
            orgExtId));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketB, "r-2"),
            "src-2",
            String.valueOf(orgId),
            orgExtId));

    String body =
        negotiationStateChangedPayload(
            negotiationId,
            NegotiatorNegotiationEvent.UNPAUSE.toState(),
            NegotiatorNegotiationEvent.PAUSE.toState(),
            NegotiatorNegotiationEvent.PAUSE.name());
    performWebhook("state-change-w1", Instant.now(), body).andExpect(status().isOk());

    verify(helpdeskClient)
        .updateNegotiationState(eq(ticketA), eq(NegotiatorNegotiationEvent.PAUSE.toState()), any());
    verify(helpdeskClient)
        .updateNegotiationState(eq(ticketB), eq(NegotiatorNegotiationEvent.PAUSE.toState()), any());
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
