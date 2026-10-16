package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;

/** Strategy interface for processing a verified helpdesk callback event */
public interface HelpdeskEventHandler<T extends HelpdeskCallbackEvent> {

  /** Returns the callback type this handler processes. */
  HelpdeskCallbackType getSupportedType();

  /**
   * Decides the outbound effects for the callback, without performing any external write I/O.
   *
   * @param event the parsed, verified callback event
   * @param config the organization configuration resolved from the inbound URL
   * @return the handler result containing intents to enqueue and an optional originated post marker
   */
  HandlerResult handle(T event, OrganizationConfig config);
}
