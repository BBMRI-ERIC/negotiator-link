package eu.bbmri_eric.negotiator.negotiatorlink.e2e;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiation;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiationApprovedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiationStateChangedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiationUpdatedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.postAddedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.resource;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.resourceStateUpdatedPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.signedWebhookPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.utf8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.AttachmentMetadataDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.util.NegotiatorNegotiationEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.util.NegotiatorResourceEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class NegotiatorToZammadE2ETest {

  private static final String ZAMMAD_BASE = "https://zammad.mhh.de";
  private static final String TICKETS_URL = ZAMMAD_BASE + "/api/v1/tickets";
  private static final String TICKET_ARTICLES_URL = ZAMMAD_BASE + "/api/v1/ticket_articles";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final Long ORG_ID = 1L;
  private static final String PAUSED_SYSTEM_COMMENT = "Negotiation status changed to 'Paused'";
  private static final String CHECKING_AVAILABILITY_SYS_COMMENT =
      "Resource 'src-e2e-1' changed the status to 'Checking Availability'";

  @Autowired private MockMvc mockMvc;
  @Autowired private TicketMappingRepository ticketMappingRepository;
  @Autowired private RestClient.Builder restClientBuilder;

  @Value("${negotiator-link.webhook.secret}")
  private String secret;

  @MockitoBean private NegotiatorClient negotiatorClient;

  private MockRestServiceServer zammadServer;

  @BeforeEach
  void setUp() {
    zammadServer = MockRestServiceServer.bindTo(restClientBuilder).build();
  }

  @Test
  void negotiationApproved_createsTicketInZammad() throws Exception {
    String negotiationId = "neg-e2e-1";
    var body = negotiationApprovedPayload(negotiationId);
    var resource = resource(10L, "src-1", "Resource A", ORG_EXT_ID, ORG_ID);

    when(negotiatorClient.getNegotiation(eq(negotiationId), any(OrganizationConfig.class)))
        .thenReturn(
            negotiation(negotiationId, "E2E-1", NegotiatorNegotiationEvent.APPROVE.toState()));
    when(negotiatorClient.getResources(eq(negotiationId), any(OrganizationConfig.class)))
        .thenReturn(List.of(resource));

    zammadServer
        .expect(requestTo(TICKETS_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.title").value("[NEGO-E2E-1] Access Request — Resource A"))
        .andExpect(jsonPath("$.group").value("Sales"))
        .andExpect(jsonPath("$.customer").value("negotiator-link@mhh.de"))
        .andExpect(
            jsonPath("$.negotiation_state").value(NegotiatorNegotiationEvent.APPROVE.toState()))
        .andExpect(jsonPath("$.negotiator_resource_state").value("SUBMITTED"))
        .andExpect(jsonPath("$.article.attachments").doesNotExist())
        .andRespond(withSuccess("{\"id\":1,\"number\":\"1001\"}", MediaType.APPLICATION_JSON));

    postWebhook("msg-e2e-approve", body);

    zammadServer.verify();
  }

  @Test
  void resourceStateUpdated_updatesTicketInZammad() throws Exception {
    String negotiationId = "neg-e2e-2";
    String resourceSourceId = "src-e2e-1";
    String ticketId = "2";
    var body =
        resourceStateUpdatedPayload(
            negotiationId,
            resourceSourceId,
            "SUBMITTED",
            NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.targetState(),
            NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.toString());

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketId, "res-e2e-1"),
            resourceSourceId,
            String.valueOf(ORG_ID),
            ORG_EXT_ID));

    zammadServer
        .expect(requestTo(TICKETS_URL + "/" + ticketId))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(
            jsonPath("$.negotiator_resource_state")
                .value(NegotiatorResourceEvent.MARK_AS_CHECKING_AVAILABILITY.targetState()))
        .andRespond(withSuccess());

    zammadServer
        .expect(requestTo(TICKET_ARTICLES_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.ticket_id").value(Integer.parseInt(ticketId)))
        .andExpect(jsonPath("$.body").value(CHECKING_AVAILABILITY_SYS_COMMENT))
        .andExpect(jsonPath("$.internal").value(true))
        .andRespond(withSuccess());

    postWebhook("msg-e2e-rsu", body);

    zammadServer.verify();
  }

  @Test
  void postAdded_forwardsCommentToZammad() throws Exception {
    String negotiationId = "neg-e2e-3";
    String postId = "post-e2e-1";
    String ticketId = "42";
    String resourceId = "res-e2e-2";
    var body = postAddedPayload(negotiationId, postId, ORG_ID);

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketId, resourceId),
            "src-e2e-2",
            String.valueOf(ORG_ID),
            ORG_EXT_ID));

    when(negotiatorClient.getPost(eq(negotiationId), eq(postId), any(OrganizationConfig.class)))
        .thenReturn(
            TestUtil.post(
                postId,
                "Hello from Negotiator",
                EntityModelPostDTO.TypeEnum.PRIVATE,
                String.valueOf(ORG_ID),
                "Jane Doe",
                "jane@example.com"));

    zammadServer
        .expect(requestTo(TICKET_ARTICLES_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.ticket_id").value(42))
        .andExpect(
            jsonPath("$.body").value("From: Jane Doe <jane@example.com>\n\nHello from Negotiator"))
        .andExpect(jsonPath("$.internal").value(false))
        .andRespond(withSuccess());

    postWebhook("msg-e2e-post", body);

    zammadServer.verify();
  }

  @Test
  void negotiationStateChanged_updatesNegotiationStateOnAllZammadTickets() throws Exception {
    String negotiationId = "neg-e2e-state-1";
    String ticketId1 = "101";
    String ticketId2 = "102";
    String postId = "post-e2e-state-1";

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketId1, "res-state-1"),
            "src-state-1",
            String.valueOf(ORG_ID),
            ORG_EXT_ID));
    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketId2, "res-state-2"),
            "src-state-2",
            String.valueOf(ORG_ID),
            ORG_EXT_ID));

    var body =
        negotiationStateChangedPayload(
            negotiationId,
            NegotiatorNegotiationEvent.UNPAUSE.toState(),
            NegotiatorNegotiationEvent.PAUSE.toState(),
            NegotiatorNegotiationEvent.PAUSE.name(),
            postId);

    when(negotiatorClient.getPost(eq(negotiationId), eq(postId), any(OrganizationConfig.class)))
        .thenReturn(
            TestUtil.post(
                postId,
                "Hello from Negotiator",
                EntityModelPostDTO.TypeEnum.PUBLIC,
                String.valueOf(ORG_ID),
                "Jane Doe",
                "jane@example.com"));

    zammadServer
        .expect(requestTo(TICKETS_URL + "/" + ticketId1))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(
            jsonPath("$.negotiation_state").value(NegotiatorNegotiationEvent.PAUSE.toState()))
        .andRespond(withSuccess());

    zammadServer
        .expect(requestTo(TICKET_ARTICLES_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.ticket_id").value(Integer.parseInt(ticketId1)))
        .andExpect(jsonPath("$.body").value(PAUSED_SYSTEM_COMMENT))
        .andExpect(jsonPath("$.internal").value(true))
        .andRespond(withSuccess());

    zammadServer
        .expect(requestTo(TICKET_ARTICLES_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.ticket_id").value(Integer.parseInt(ticketId1)))
        .andExpect(
            jsonPath("$.body").value("From: Jane Doe <jane@example.com>\n\nHello from Negotiator"))
        .andExpect(jsonPath("$.internal").value(false))
        .andRespond(withSuccess());

    zammadServer
        .expect(requestTo(TICKETS_URL + "/" + ticketId2))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(
            jsonPath("$.negotiation_state").value(NegotiatorNegotiationEvent.PAUSE.toState()))
        .andRespond(withSuccess());

    zammadServer
        .expect(requestTo(TICKET_ARTICLES_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.ticket_id").value(Integer.parseInt(ticketId2)))
        .andExpect(jsonPath("$.body").value(PAUSED_SYSTEM_COMMENT))
        .andExpect(jsonPath("$.internal").value(true))
        .andRespond(withSuccess());

    zammadServer
        .expect(requestTo(TICKET_ARTICLES_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.ticket_id").value(Integer.parseInt(ticketId2)))
        .andExpect(
            jsonPath("$.body").value("From: Jane Doe <jane@example.com>\n\nHello from Negotiator"))
        .andExpect(jsonPath("$.internal").value(false))
        .andRespond(withSuccess());

    postWebhook("msg-e2e-state", body);

    zammadServer.verify();
  }

  @Test
  void negotiationUpdated_postsUpdateCommentAndForwardsNewAttachments() throws Exception {
    String negotiationId = "neg-e2e-upd-1";
    String ticketId = "77";
    String resourceId = "20";
    var resource = resource(20L, "src-upd-1", "Resource U", ORG_EXT_ID, ORG_ID);

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketId, resourceId),
            "src-upd-1",
            String.valueOf(ORG_ID),
            ORG_EXT_ID));

    when(negotiatorClient.getNegotiation(eq(negotiationId), any(OrganizationConfig.class)))
        .thenReturn(negotiation(negotiationId, "E2E-UPD-1", "IN_PROGRESS"));
    when(negotiatorClient.getResources(eq(negotiationId), any(OrganizationConfig.class)))
        .thenReturn(List.of(resource));

    var publicAttachment =
        new AttachmentMetadataDTO()
            .id("att-public")
            .name("requestor.txt")
            .contentType("text/plain")
            .size(5L);
    var privateAttachment =
        new AttachmentMetadataDTO()
            .id("att-private")
            .organizationId(String.valueOf(ORG_ID))
            .name("internal.txt")
            .contentType("text/plain")
            .size(7L);
    when(negotiatorClient.getAttachments(eq(negotiationId), any(OrganizationConfig.class)))
        .thenReturn(List.of(publicAttachment, privateAttachment));
    when(negotiatorClient.downloadAttachment(eq("att-public"), any(OrganizationConfig.class)))
        .thenReturn("hello".getBytes());

    // First article: the negotiation-update comment, with no attachments.
    zammadServer
        .expect(requestTo(TICKET_ARTICLES_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.ticket_id").value(77))
        .andExpect(jsonPath("$.content_type").value("text/html"))
        .andExpect(jsonPath("$.internal").value(false))
        .andExpect(jsonPath("$.attachments").doesNotExist())
        .andRespond(withSuccess());

    // Second article: the new negotiation-level attachment only.
    zammadServer
        .expect(requestTo(TICKET_ARTICLES_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.ticket_id").value(77))
        .andExpect(jsonPath("$.attachments[0].filename").value("requestor.txt"))
        .andExpect(jsonPath("$.attachments[0].data").value("aGVsbG8="))
        .andExpect(jsonPath("$.attachments[0]['mime-type']").value("text/plain"))
        .andExpect(jsonPath("$.attachments[1]").doesNotExist())
        .andRespond(withSuccess());

    postWebhook("msg-e2e-updated", negotiationUpdatedPayload(negotiationId));

    zammadServer.verify();
  }

  private void postWebhook(String webhookId, String body) throws Exception {
    var timestamp = Instant.now();
    mockMvc
        .perform(
            post("/webhooks/negotiator")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Webhook-Id", webhookId)
                .header("Webhook-Timestamp", timestamp.getEpochSecond())
                .header(
                    "Webhook-Signature", signedWebhookPayload(secret, webhookId, timestamp, body))
                .content(utf8(body)))
        .andExpect(status().isOk());
  }
}
