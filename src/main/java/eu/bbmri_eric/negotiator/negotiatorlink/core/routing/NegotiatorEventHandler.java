package eu.bbmri_eric.negotiator.negotiatorlink.core.routing;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Strategy interface for handling a single Negotiator webhook event type.
 *
 * <p>Each implementation is responsible for exactly one event type (returned by {@link
 * #getSupportedEventType()}) and performs all domain logic for that event inside {@link
 * #handle(JsonNode)}. Routing logic must never live inside an implementation — the {@link
 * EventRouter} resolves the correct handler before invoking it.
 */
public interface NegotiatorEventHandler {

  /**
   * Returns the Negotiator webhook event type this handler supports, e.g. {@code
   * "negotiation.state.updated"}.
   */
  String getSupportedEventType();

  /**
   * Processes the {@code data} node of a Negotiator webhook envelope for the supported event type.
   *
   * @param data the {@code data} field of the webhook payload as a {@link JsonNode}
   */
  void handle(JsonNode data);
}
