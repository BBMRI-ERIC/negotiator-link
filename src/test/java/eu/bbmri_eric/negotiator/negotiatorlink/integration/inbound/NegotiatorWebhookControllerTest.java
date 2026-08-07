package eu.bbmri_eric.negotiator.negotiatorlink.integration.inbound;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.signedWebhookPayload;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.utf8;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class NegotiatorWebhookControllerTest {

  private static final String BODY = "{\"event\":\"test\"}";

  @Autowired private MockMvc mockMvc;

  @Value("${negotiatorlink.webhook.secret}")
  private String secret;

  @Test
  void receiveWebhook_whenValidSignature_returns200() throws Exception {
    var webhookId = "msg-001";
    var timestamp = Instant.now();

    mockMvc
        .perform(
            post("/webhooks/negotiator")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Webhook-Id", webhookId)
                .header("Webhook-Timestamp", timestamp.getEpochSecond())
                .header(
                    "Webhook-Signature", signedWebhookPayload(secret, webhookId, timestamp, BODY))
                .content(utf8(BODY)))
        .andExpect(status().isOk());
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

    mockMvc
        .perform(
            post("/webhooks/negotiator")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Webhook-Id", webhookId)
                .header("Webhook-Timestamp", staleTimestamp.getEpochSecond())
                .header(
                    "Webhook-Signature",
                    signedWebhookPayload(secret, webhookId, staleTimestamp, BODY))
                .content(utf8(BODY)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void receiveWebhook_whenDuplicate_returns200() throws Exception {
    var webhookId = "msg-005";
    var timestamp = Instant.now();
    var signature = signedWebhookPayload(secret, webhookId, timestamp, BODY);

    // first delivery — marks as seen
    mockMvc
        .perform(
            post("/webhooks/negotiator")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Webhook-Id", webhookId)
                .header("Webhook-Timestamp", timestamp.getEpochSecond())
                .header("Webhook-Signature", signature)
                .content(utf8(BODY)))
        .andExpect(status().isOk());

    // second delivery with same id — acknowledged silently
    mockMvc
        .perform(
            post("/webhooks/negotiator")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Webhook-Id", webhookId)
                .header("Webhook-Timestamp", timestamp.getEpochSecond())
                .header("Webhook-Signature", signature)
                .content(utf8(BODY)))
        .andExpect(status().isOk());
  }
}
