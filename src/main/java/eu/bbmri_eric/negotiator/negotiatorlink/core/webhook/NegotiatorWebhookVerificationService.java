package eu.bbmri_eric.negotiator.negotiatorlink.core.webhook;

import jakarta.servlet.http.HttpServletRequest;

/** Verifies inbound Negotiator webhook requests. */
public interface NegotiatorWebhookVerificationService {

  /** Verifies signature and timestamp. Throws {@link WebhookAuthenticationException} on failure. */
  void verify(HttpServletRequest request, byte[] body);
}
