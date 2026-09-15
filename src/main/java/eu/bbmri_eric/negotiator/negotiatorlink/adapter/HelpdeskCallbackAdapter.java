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
   * Verify the authenticity of an inbound callback from the help desk platform. Returns false if
   * verification fails.
   */
  boolean verifyCallback(HttpHeaders headers, byte[] body, String callbackSecret);

  /**
   * Extracts the platform-specific webhook delivery ID from the request headers. Returns empty if
   * the platform does not provide a delivery ID header.
   */
  Optional<String> extractCallbackId(HttpHeaders headers);

  /**
   * Parse a raw help desk callback payload into the middleware's internal HelpdeskCallbackEvent
   * model. Returns empty if the payload should be ignored (e.g. internal comment).
   */
  Optional<HelpdeskCallbackEvent> parseCallback(Map<String, Object> payload);
}
