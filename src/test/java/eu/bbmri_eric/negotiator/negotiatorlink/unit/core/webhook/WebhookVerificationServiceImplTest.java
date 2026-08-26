package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.webhook;

import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.buildWebhookRequest;
import static eu.bbmri_eric.negotiator.negotiatorlink.util.TestUtil.signedWebhookPayload;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.WebhookAuthenticationException;
import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.WebhookVerificationServiceImpl;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class WebhookVerificationServiceImplTest {

  private static final String SECRET = "whsec_dGVzdC1zZWNyZXQtdGhhdC1pcy1sb25nLWVub3VnaA==";
  private static final String WEBHOOK_ID = "msg_abc123";
  private static final String BODY = "{\"event\":\"test\"}";

  private WebhookVerificationServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new WebhookVerificationServiceImpl(SECRET);
  }

  @Test
  void verify_whenValidRequest_doesNotThrow() {
    var request =
        buildWebhookRequest(
            WEBHOOK_ID, signedWebhookPayload(SECRET, WEBHOOK_ID, Instant.now(), BODY));
    assertThatNoException()
        .isThrownBy(() -> service.verify(request, BODY.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void verify_whenSignatureTampered_throwsWebhookAuthenticationException() {
    var request = buildWebhookRequest(WEBHOOK_ID, "v1,invalidsignature==");
    assertThatThrownBy(() -> service.verify(request, BODY.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(WebhookAuthenticationException.class);
  }

  @Test
  void verify_whenTimestampTooOld_throwsWebhookAuthenticationException() {
    var staleInstant = Instant.now().minusSeconds(6 * 60);
    var request =
        buildWebhookRequest(
            WEBHOOK_ID, signedWebhookPayload(SECRET, WEBHOOK_ID, staleInstant, BODY), staleInstant);
    assertThatThrownBy(() -> service.verify(request, BODY.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(WebhookAuthenticationException.class);
  }

  @Test
  void verify_whenSignatureMissing_throwsWebhookAuthenticationException() {
    var request = new MockHttpServletRequest();
    request.addHeader("Webhook-Id", WEBHOOK_ID);
    request.addHeader("Webhook-Timestamp", String.valueOf(Instant.now().getEpochSecond()));
    // Webhook-Signature header intentionally omitted
    assertThatThrownBy(() -> service.verify(request, BODY.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(WebhookAuthenticationException.class)
        .hasMessageContaining("Webhook-Signature");
  }

  @Test
  void constructor_whenSecretIsBlank_throwsIllegalStateException() {
    assertThatThrownBy(() -> new WebhookVerificationServiceImpl(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NEGOTIATOR_WEBHOOK_SECRET");
  }
}
