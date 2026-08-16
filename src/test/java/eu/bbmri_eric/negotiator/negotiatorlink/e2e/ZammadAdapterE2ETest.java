package eu.bbmri_eric.negotiator.negotiatorlink.e2e;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiation;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.negotiationApprovedPayload;
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
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelPostDTO;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

@SpringBootTest
@AutoConfigureMockMvc
class ZammadAdapterE2ETest {

  private static final String ZAMMAD_BASE = "https://zammad.mhh.de";
  private static final String ORG_EXT_ID = "bbmri:DE:MHH";
  private static final Long ORG_ID = 1L;

  @Autowired private MockMvc mockMvc;
  @Autowired private TicketMappingRepository ticketMappingRepository;
  @Autowired private RestClient.Builder restClientBuilder;

  @Value("${negotiatorlink.webhook.secret}")
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
    var resource = resource(10L, "src-1", "Biobank A", ORG_EXT_ID, ORG_ID);

    when(negotiatorClient.getNegotiation(eq(negotiationId), any(OrganizationConfig.class)))
        .thenReturn(negotiation(negotiationId, "NEGO-E2E-1", "IN_PROGRESS"));
    when(negotiatorClient.getResources(eq(negotiationId), any(OrganizationConfig.class)))
        .thenReturn(List.of(resource));

    zammadServer
        .expect(requestTo(ZAMMAD_BASE + "/api/v1/tickets"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.title").value("[NEGO-E2E-1] Biobank A — Access Request"))
        .andExpect(jsonPath("$.group").value("Sales"))
        .andExpect(jsonPath("$.negotiation_state").value("IN_PROGRESS"))
        .andExpect(jsonPath("$.negotiator_resource_state").value("SUBMITTED"))
        .andRespond(withSuccess("{\"id\":1,\"number\":\"1001\"}", MediaType.APPLICATION_JSON));

    postWebhook("msg-e2e-approve", body);

    zammadServer.verify();
  }

  @Test
  void resourceStateUpdated_updatesTicketInZammad() throws Exception {
    String negotiationId = "neg-e2e-2";
    String resourceId = "res-e2e-1";
    String ticketId = "t-2";
    var body =
        resourceStateUpdatedPayload(
            negotiationId, resourceId, "SUBMITTED", "RESOURCE_AVAILABLE", "MARK_AS_AVAILABLE");

    ticketMappingRepository.save(
        new TicketMapping(
            new TicketMappingId(negotiationId, ticketId, resourceId),
            "src-e2e-1",
            String.valueOf(ORG_ID),
            ORG_EXT_ID));

    zammadServer
        .expect(requestTo(ZAMMAD_BASE + "/api/v1/tickets/" + ticketId))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(jsonPath("$.negotiator_resource_state").value("RESOURCE_AVAILABLE"))
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

    when(negotiatorClient.getPostText(eq(postId), any(OrganizationConfig.class)))
        .thenReturn(
            TestUtil.post(
                postId,
                "Hello from Negotiator",
                EntityModelPostDTO.TypeEnum.PRIVATE,
                String.valueOf(ORG_ID)));

    zammadServer
        .expect(requestTo(ZAMMAD_BASE + "/api/v1/ticket_articles"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.ticket_id").value(42))
        .andExpect(jsonPath("$.body").value("Hello from Negotiator"))
        .andExpect(jsonPath("$.internal").value(false))
        .andRespond(withSuccess());

    postWebhook("msg-e2e-post", body);

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
