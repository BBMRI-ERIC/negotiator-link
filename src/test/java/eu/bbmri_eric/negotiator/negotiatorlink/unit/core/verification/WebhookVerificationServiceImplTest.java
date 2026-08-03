package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.standardwebhooks.Webhook;
import eu.bbmri_eric.negotiator.negotiatorlink.core.verification.WebhookAuthenticationException;
import eu.bbmri_eric.negotiator.negotiatorlink.core.verification.WebhookVerificationServiceImpl;
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
  private Webhook webhook;

  @BeforeEach
  void setUp() {
    service = new WebhookVerificationServiceImpl(SECRET);
    webhook = new Webhook(SECRET);
  }

  @Test
  void verify_whenValidRequest_doesNotThrow() {
    var request = buildRequest(WEBHOOK_ID, sign(WEBHOOK_ID, Instant.now(), BODY));
    assertThatNoException()
        .isThrownBy(() -> service.verify(request, BODY.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  void verify_whenSignatureTampered_throwsWebhookAuthenticationException() {
    var request = buildRequest(WEBHOOK_ID, "v1,invalidsignature==");
    assertThatThrownBy(() -> service.verify(request, BODY.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(WebhookAuthenticationException.class);
  }

  @Test
  void verify_whenTimestampTooOld_throwsWebhookAuthenticationException() {
    var staleInstant = Instant.now().minusSeconds(6 * 60);
    var request = buildRequest(WEBHOOK_ID, sign(WEBHOOK_ID, staleInstant, BODY), staleInstant);
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
  void checkAndMarkSeen_whenFirstCall_returnsFalse() {
    assertThat(service.checkAndMarkSeen("new-id-1")).isFalse();
  }

  @Test
  void checkAndMarkSeen_whenCalledTwice_returnsTrueOnSecond() {
    service.checkAndMarkSeen("new-id-2");
    assertThat(service.checkAndMarkSeen("new-id-2")).isTrue();
  }

  @Test
  void constructor_whenSecretIsBlank_throwsIllegalStateException() {
    assertThatThrownBy(() -> new WebhookVerificationServiceImpl(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("NEGOTIATOR_WEBHOOK_SECRET");
  }

  private MockHttpServletRequest buildRequest(String webhookId, String signature) {
    return buildRequest(webhookId, signature, Instant.now());
  }

  private MockHttpServletRequest buildRequest(
      String webhookId, String signature, Instant timestamp) {
    var request = new MockHttpServletRequest();
    request.addHeader("Webhook-Id", webhookId);
    request.addHeader("Webhook-Timestamp", String.valueOf(timestamp.getEpochSecond()));
    request.addHeader("Webhook-Signature", signature);
    return request;
  }

  private String sign(String msgId, Instant timestamp, String body) {
    try {
      return webhook.sign(msgId, timestamp.getEpochSecond(), body);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
