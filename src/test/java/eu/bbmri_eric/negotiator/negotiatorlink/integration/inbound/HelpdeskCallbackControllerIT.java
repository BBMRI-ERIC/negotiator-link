package eu.bbmri_eric.negotiator.negotiatorlink.integration.inbound;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.hmacSha1Hex;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import eu.bbmri_eric.negotiator.negotiatorlink.integration.util.IntegrationBase;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationBase
class HelpdeskCallbackControllerIT {

  private static final String KNOWN_ORG = "bbmri:DE:MHH";
  private static final String UNKNOWN_ORG = "bbmri:XX:UNKNOWN";
  private static final String CALLBACK_SECRET = "test-callback-secret";
  private static final String BODY = "{\"ticket\":{\"id\":1}}";

  @Autowired private MockMvc mockMvc;

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
}
