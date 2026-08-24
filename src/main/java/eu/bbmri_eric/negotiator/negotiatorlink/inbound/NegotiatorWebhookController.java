package eu.bbmri_eric.negotiator.negotiatorlink.inbound;

import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.NegotiatorEventRouter;
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
  private final NegotiatorEventRouter negotiatorEventRouter;

  public NegotiatorWebhookController(
      WebhookVerificationService verifier, NegotiatorEventRouter negotiatorEventRouter) {
    this.verifier = verifier;
    this.negotiatorEventRouter = negotiatorEventRouter;
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
    negotiatorEventRouter.route(body);
    return ResponseEntity.ok().build();
  }
}
