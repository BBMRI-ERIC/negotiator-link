package eu.bbmri_eric.negotiator.negotiatorlink.adapter;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;

/**
 * Inbound side of a help desk integration: authenticate and translate callbacks received from the
 * help desk platform into the middleware's internal model.
 */
public interface HelpdeskCallbackAdapter extends HelpdeskTyped {

  /**
   * Verify the authenticity of an inbound callback against the raw request body, using the
   * platform's scheme (e.g. a signature) and the organization's callback secret. Must return {@code
   * false} on any verification failure rather than throwing.
   *
   * @param headers the inbound request headers
   * @param body the raw request body exactly as received
   * @param callbackSecret the shared secret configured for the organization
   * @return {@code true} if the callback is authentic, {@code false} otherwise
   */
  boolean verifyCallback(HttpHeaders headers, byte[] body, String callbackSecret);

  /**
   * Extract the platform's unique delivery id from the request headers, used to deduplicate
   * redelivered callbacks.
   *
   * @param headers the inbound request headers
   * @return the delivery id, or empty if the platform provides none
   */
  Optional<String> extractCallbackId(HttpHeaders headers);

  /**
   * Translate a raw help desk callback payload into the middleware's internal {@link
   * HelpdeskCallbackEvent}. Pure translation with no external calls.
   *
   * @param payload the parsed callback body
   * @return the translated event, or empty if the payload carries nothing actionable and should be
   *     ignored
   */
  Optional<HelpdeskCallbackEvent> parseCallback(Map<String, Object> payload);
}
