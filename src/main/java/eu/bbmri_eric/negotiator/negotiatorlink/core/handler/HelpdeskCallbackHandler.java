package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;
import eu.bbmri_eric.negotiator.negotiatorlink.core.reliability.PendingIntent;
import java.util.List;

/** Strategy interface for processing a verified helpdesk callback event */
public interface HelpdeskCallbackHandler<T extends HelpdeskCallbackEvent> {

  /** Returns the callback type this handler processes. */
  HelpdeskCallbackType getSupportedType();

  /**
   * Decides the outbound effects for the callback, without performing any external write I/O.
   *
   * @param event the parsed, verified callback event
   * @param config the organization configuration resolved from the inbound URL
   * @return the outbound intents to enqueue (possibly empty)
   */
  List<PendingIntent> handle(T event, OrganizationConfig config);
}
