package eu.bbmri_eric.negotiator.negotiatorlink.integration.inbound;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.hmacSha1Hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.bbmri_eric.negotiator.negotiatorlink.core.client.NegotiatorClient;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.InboxEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMapping;
import eu.bbmri_eric.negotiator.negotiatorlink.core.entity.TicketMappingId;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxPoller;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxStatus;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.InboxEventRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.core.repository.TicketMappingRepository;
import eu.bbmri_eric.negotiator.negotiatorlink.integration.util.IntegrationBase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationBase
class HelpdeskCallbackControllerIT {

  private static final String KNOWN_ORG = "bbmri:DE:MHH";
  private static final String UNKNOWN_ORG = "bbmri:XX:UNKNOWN";
  private static final String CALLBACK_SECRET = "test-callback-secret";
  private static final String BODY = "{\"ticket\":{\"id\":1}}";

  @Autowired private MockMvc mockMvc;
  @Autowired private InboxPoller inboxPoller;
  @Autowired private InboxEventRepository inboxEventRepository;
  @Autowired private TicketMappingRepository ticketMappingRepository;

  @MockitoBean private NegotiatorClient negotiatorClient;

  @Test
  void receiveCallback_validSignature_returns200() throws Exception {
    byte[] bodyBytes = BODY.getBytes(StandardCharsets.UTF_8);
    String sig = "sha1=" + hmacSha1Hex(CALLBACK_SECRET, bodyBytes);

    mockMvc
        .perform(
            post("/webhooks/helpdesk/{org}", KNOWN_ORG)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Hub-Signature", sig)
                .content(bodyBytes))
        .andExpect(status().isOk());
  }

  @Test
  void receiveCallback_invalidSignature_returns401() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/helpdesk/{org}", KNOWN_ORG)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Hub-Signature", "sha1=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
                .content(BODY.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void receiveCallback_missingSignature_returns401() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/helpdesk/{org}", KNOWN_ORG)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(BODY.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void receiveCallback_unknownOrganization_returns404() throws Exception {
    mockMvc
        .perform(
            post("/webhooks/helpdesk/{org}", UNKNOWN_ORG)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Hub-Signature", "sha1=anything")
                .content(BODY.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isNotFound());
  }

  @Test
  void receiveCallback_duplicateDelivery_returns200AndIsDiscarded() throws Exception {
    byte[] bodyBytes = BODY.getBytes(StandardCharsets.UTF_8);
    String sig = "sha1=" + hmacSha1Hex(CALLBACK_SECRET, bodyBytes);
    String deliveryId = "duplicate-delivery-id-" + System.nanoTime();

    mockMvc
        .perform(
            post("/webhooks/helpdesk/{org}", KNOWN_ORG)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Hub-Signature", sig)
                .header("X-Zammad-Delivery", deliveryId)
                .content(bodyBytes))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/webhooks/helpdesk/{org}", KNOWN_ORG)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Hub-Signature", sig)
                .header("X-Zammad-Delivery", deliveryId)
                .content(bodyBytes))
        .andExpect(status().isOk());
  }

  @Test
  void receiveCallback_negotiationNotObtainedFromNegotiator_rowEndsFailed() throws Exception {
    ticketMappingRepository.save(
        new TicketMapping(new TicketMappingId("neg-null-1", "1", "r-1"), "src-1", "42", KNOWN_ORG));

    when(negotiatorClient.getNegotiation(any(), any())).thenReturn(null);

    byte[] bodyBytes =
        loadFixture("webhookPayloadWhenNewPublicArticle.json").getBytes(StandardCharsets.UTF_8);
    String sig = "sha1=" + hmacSha1Hex(CALLBACK_SECRET, bodyBytes);
    String deliveryId = "null-negotiation-delivery-" + System.nanoTime();

    mockMvc
        .perform(
            post("/webhooks/helpdesk/{org}", KNOWN_ORG)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Hub-Signature", sig)
                .header("X-Zammad-Delivery", deliveryId)
                .content(bodyBytes))
        .andExpect(status().isOk());
    inboxPoller.poll();

    InboxEvent row =
        inboxEventRepository.findBySourceAndDeliveryId(KNOWN_ORG, deliveryId).orElseThrow();
    assertThat(row.getStatus()).isEqualTo(InboxStatus.FAILED);
    assertThat(row.getLastError()).isNotNull();
    assertThat(row.getProcessedAt()).isNotNull();
    assertThat(row.getAttemptCount()).isEqualTo(1);
  }

  private static String loadFixture(String filename) {
    try {
      return new ClassPathResource("fixtures/zammad/" + filename)
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
