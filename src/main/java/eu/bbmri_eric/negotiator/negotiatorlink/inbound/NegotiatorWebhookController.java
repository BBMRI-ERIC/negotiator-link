package eu.bbmri_eric.negotiator.negotiatorlink.inbound;

import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.EventRouter;
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
  private final EventRouter eventRouter;

  public NegotiatorWebhookController(WebhookVerificationService verifier, EventRouter eventRouter) {
    this.verifier = verifier;
    this.eventRouter = eventRouter;
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
    eventRouter.route(body);
    return ResponseEntity.ok().build();
  }
}
