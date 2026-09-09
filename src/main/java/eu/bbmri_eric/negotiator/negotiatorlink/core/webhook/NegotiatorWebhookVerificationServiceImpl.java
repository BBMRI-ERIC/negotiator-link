package eu.bbmri_eric.negotiator.negotiatorlink.core.webhook;

import com.standardwebhooks.Webhook;
import com.standardwebhooks.exceptions.WebhookVerificationException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class NegotiatorWebhookVerificationServiceImpl
    implements NegotiatorWebhookVerificationService {

  private final Webhook webhook;

  public NegotiatorWebhookVerificationServiceImpl(
      @Value("${negotiator-link.webhook.secret}") String secret) {
    if (StringUtils.isBlank(secret)) {
      throw new IllegalStateException(
          "NEGOTIATOR_WEBHOOK_SECRET must not be blank — set the environment variable");
    }
    this.webhook = new Webhook(secret);
  }

  @Override
  public void verify(HttpServletRequest request, byte[] body) {
    var headers =
        Map.of(
            "webhook-id", List.of(header(request, "Webhook-Id")),
            "webhook-timestamp", List.of(header(request, "Webhook-Timestamp")),
            "webhook-signature", List.of(header(request, "Webhook-Signature")));
    try {
      webhook.verify(new String(body, StandardCharsets.UTF_8), headers);
    } catch (WebhookVerificationException e) {
      throw new WebhookAuthenticationException("Webhook verification failed: " + e.getMessage(), e);
    }
  }

  private String header(HttpServletRequest request, String name) {
    var value = request.getHeader(name);
    if (StringUtils.isBlank(value)) {
      throw new WebhookAuthenticationException("Missing required header: " + name);
    }
    return value;
  }
}
