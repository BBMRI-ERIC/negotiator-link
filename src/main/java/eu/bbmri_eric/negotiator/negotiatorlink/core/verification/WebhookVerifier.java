package eu.bbmri_eric.negotiator.negotiatorlink.core.verification;

import jakarta.servlet.http.HttpServletRequest;

public interface WebhookVerifier extends ReplayCache {

  /**
   * Verifies the authenticity of an inbound webhook. Throws {@link WebhookAuthenticationException}
   * on failure.
   */
  void verify(HttpServletRequest request, byte[] body);
}
