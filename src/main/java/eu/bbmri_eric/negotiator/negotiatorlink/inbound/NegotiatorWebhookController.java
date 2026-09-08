package eu.bbmri_eric.negotiator.negotiatorlink.inbound;

import eu.bbmri_eric.negotiator.negotiatorlink.core.routing.NegotiatorEventRouter;
import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.NegotiatorWebhookVerificationService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.ReplayGuard;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class NegotiatorWebhookController {

  private final NegotiatorWebhookVerificationService verifier;
  private final ReplayGuard replayGuard;
  private final NegotiatorEventRouter negotiatorEventRouter;

  public NegotiatorWebhookController(
      NegotiatorWebhookVerificationService verifier,
      ReplayGuard replayGuard,
      NegotiatorEventRouter negotiatorEventRouter) {
    this.verifier = verifier;
    this.replayGuard = replayGuard;
    this.negotiatorEventRouter = negotiatorEventRouter;
  }

  @PostMapping("/webhooks/negotiator")
  ResponseEntity<Void> receiveWebhook(HttpServletRequest request, @RequestBody byte[] body) {
    verifier.verify(request, body);
    String webhookId = request.getHeader("Webhook-Id");
    if (replayGuard.alreadySeen(webhookId)) {
      log.debug("Duplicate webhook {} — acknowledged and discarded", webhookId);
      return ResponseEntity.ok().build();
    }
    log.info("Received webhook {}", webhookId);
    negotiatorEventRouter.route(body);
    return ResponseEntity.ok().build();
  }
}
