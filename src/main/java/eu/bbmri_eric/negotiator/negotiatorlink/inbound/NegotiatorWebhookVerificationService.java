package eu.bbmri_eric.negotiator.negotiatorlink.inbound;

import jakarta.servlet.http.HttpServletRequest;

/** Verifies inbound Negotiator webhook requests. */
public interface NegotiatorWebhookVerificationService {

  /** Verifies signature and timestamp. Throws {@link WebhookAuthenticationException} on failure. */
  void verify(HttpServletRequest request, byte[] body);
}
