package eu.bbmri_eric.negotiator.negotiatorlink.inbound;

import eu.bbmri_eric.negotiator.negotiatorlink.core.verification.WebhookVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class NegotiatorWebhookController {

  private final WebhookVerificationService verifier;

  public NegotiatorWebhookController(WebhookVerificationService verifier) {
    this.verifier = verifier;
  }

  @PostMapping("/webhooks/negotiator")
  ResponseEntity<Void> receiveWebhook(HttpServletRequest request, @RequestBody byte[] body) {
    verifier.verify(request, body);
    String webhookId = request.getHeader("Webhook-Id");
    if (verifier.checkAndMarkSeen(webhookId)) {
      log.debug("Duplicate webhook {} — acknowledged and discarded", webhookId);
      return ResponseEntity.ok().build();
    }
    log.info("Received webhook {}", webhookId);
    return ResponseEntity.ok().build();
  }
}
