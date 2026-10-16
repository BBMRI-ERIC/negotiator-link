package eu.bbmri_eric.negotiator.negotiatorlink.inbound;

import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.EventSource;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.InboxService;
import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.NegotiatorWebhookVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class NegotiatorWebhookController {

  private final NegotiatorWebhookVerificationService verifier;
  private final InboxService inboxService;

  @PostMapping("/webhooks/negotiator")
  ResponseEntity<Void> receiveWebhook(HttpServletRequest request, @RequestBody byte[] body) {
    verifier.verify(request, body);
    String webhookId = request.getHeader("Webhook-Id");
    inboxService.capture(EventSource.NEGOTIATOR.name(), webhookId, body);
    return ResponseEntity.ok().build();
  }
}
