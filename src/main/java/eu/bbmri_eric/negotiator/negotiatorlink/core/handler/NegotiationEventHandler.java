package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import com.fasterxml.jackson.databind.JsonNode;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import java.util.List;

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
   * @return the outbound intents to enqueue (possibly empty)
   */
  List<PendingIntent> handle(JsonNode data);
}
