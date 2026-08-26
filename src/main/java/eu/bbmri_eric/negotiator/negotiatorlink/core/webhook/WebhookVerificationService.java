package eu.bbmri_eric.negotiator.negotiatorlink.core.webhook;

import jakarta.servlet.http.HttpServletRequest;

public interface WebhookVerificationService {

  /** Verifies signature and timestamp. Throws {@link WebhookAuthenticationException} on failure. */
  void verify(HttpServletRequest request, byte[] body);
}
