package eu.bbmri_eric.negotiator.negotiatorlink.core.verification;

public interface ReplayCache {

  /**
   * Returns {@code true} if this webhookId was already processed (replay prevention). On first call
   * for a given ID, records it as seen and returns {@code false}.
   */
  boolean checkAndMarkSeen(String webhookId);
}
