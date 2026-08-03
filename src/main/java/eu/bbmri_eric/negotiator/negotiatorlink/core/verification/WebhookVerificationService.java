package eu.bbmri_eric.negotiator.negotiatorlink.core.verification;

import jakarta.servlet.http.HttpServletRequest;

public interface WebhookVerificationService {

  /** Verifies signature and timestamp. Throws {@link WebhookAuthenticationException} on failure. */
  void verify(HttpServletRequest request, byte[] body);

  /**
   * Returns {@code true} if this webhookId was already processed (replay prevention). On first call
   * for a given ID, records it as seen and returns {@code false}.
   */
  boolean checkAndMarkSeen(String webhookId);
}
