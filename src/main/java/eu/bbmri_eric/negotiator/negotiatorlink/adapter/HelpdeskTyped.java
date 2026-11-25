package eu.bbmri_eric.negotiator.negotiatorlink.adapter;

import eu.bbmri_eric.negotiator.negotiatorlink.core.config.HelpdeskType;

/** Common identity for help desk adapters, used for auto-registration by {@link HelpdeskType}. */
public interface HelpdeskTyped {

  /**
   * Returns the help desk type this adapter handles. Used as the routing key, so each type must be
   * claimed by exactly one {@link HelpdeskClient} and one {@link HelpdeskCallbackAdapter}.
   *
   * @return the platform this adapter integrates with
   */
  HelpdeskType getType();
}
