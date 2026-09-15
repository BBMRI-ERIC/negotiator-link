package eu.bbmri_eric.negotiator.negotiatorlink.inbound;

import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.UnknownOrganizationException;
import eu.bbmri_eric.negotiator.negotiatorlink.core.webhook.WebhookAuthenticationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps webhook-endpoint domain exceptions to HTTP responses in one place. */
@Slf4j
@RestControllerAdvice
public class WebhookExceptionHandler {

  @ExceptionHandler(WebhookAuthenticationException.class)
  ResponseEntity<Void> handleWebhookAuthenticationException(WebhookAuthenticationException ex) {
    log.warn("Webhook authentication failed: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
  }

  @ExceptionHandler(UnknownOrganizationException.class)
  ResponseEntity<Void> handleUnknownOrganizationException(UnknownOrganizationException ex) {
    log.warn("Webhook for unknown organization: {}", ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
  }
}
