package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import com.fasterxml.jackson.databind.JsonNode;

/** Strategy interface for handling a single Negotiator webhook event type. */
public interface NegotiationEventHandler {

  /**
   * Returns the Negotiator webhook event type this handler supports, e.g. {@code
   * "negotiation.state.updated"}.
   */
  String getSupportedEventType();

  /**
   * Decides the outbound effects for the Negotiator webhook, without performing any external write
   * I/O.
   *
   * @param data the webhook payload as a {@link JsonNode}
   * @return the handler result containing intents to enqueue and an optional originated post marker
   */
  HandlerResult handle(JsonNode data);
}
