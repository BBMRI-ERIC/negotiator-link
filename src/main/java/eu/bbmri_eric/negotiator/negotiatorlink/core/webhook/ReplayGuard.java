package eu.bbmri_eric.negotiator.negotiatorlink.core.webhook;

/**
 * Replay guard for inbound webhooks. Tracks recently seen delivery IDs so duplicate deliveries can
 * be acknowledged and discarded. Shared by the Negotiator and help desk callback flows.
 */
public interface ReplayGuard {

  /**
   * Returns {@code true} if this key was already seen (a replay). On the first call for a given
   * key, records it as seen and returns {@code false}.
   */
  boolean alreadySeen(String key);
}
