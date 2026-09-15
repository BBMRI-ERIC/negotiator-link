package eu.bbmri_eric.negotiator.negotiatorlink.core.handler;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.OrganizationConfig;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackEvent;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.HelpdeskCallbackType;

/**
 * Strategy interface for processing a verified helpdesk callback event. Implementations must be
 * Spring beans so the {@link
 * eu.bbmri_eric.negotiator.negotiatorlink.core.routing.HelpdeskEventRouter} can collect them at
 * startup.
 */
public interface HelpdeskCallbackHandler<T extends HelpdeskCallbackEvent> {

  /** Returns the callback type this handler processes. */
  HelpdeskCallbackType getSupportedType();

  /**
   * Processes the callback event for the given organization.
   *
   * @param event the parsed, verified callback event
   * @param config the organization configuration resolved from the inbound URL
   */
  void handle(T event, OrganizationConfig config);
}
